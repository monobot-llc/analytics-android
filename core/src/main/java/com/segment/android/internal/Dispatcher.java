/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment.io, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.segment.android.internal;

import android.content.Context;
import android.os.Handler;
import com.segment.android.internal.payload.BasePayload;
import com.squareup.tape.FileObjectQueue;
import com.squareup.tape.ObjectQueue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.segment.android.internal.Utils.isConnected;

public class Dispatcher {
  private static final String TASK_QUEUE_FILE_NAME = "payload_task_queue";

  final Context context;
  final Handler mainThreadHandler;
  final ObjectQueue<BasePayload> queue;
  final SegmentHTTPApi segmentHTTPApi;
  final int maxQueueSize;
  final ExecutorService flushService;
  final ExecutorService queueService;
  final Stats stats;

  public static Dispatcher create(Context context, Handler mainThreadHandler, int maxQueueSize,
      SegmentHTTPApi segmentHTTPApi, Stats stats) {
    FileObjectQueue.Converter<BasePayload> converter = new PayloadConverter();
    File queueFile = new File(context.getFilesDir(), TASK_QUEUE_FILE_NAME);
    FileObjectQueue<BasePayload> queue;
    try {
      queue = new FileObjectQueue<BasePayload>(queueFile, converter);
    } catch (IOException e) {
      throw new RuntimeException("Unable to create file queue.", e);
    }
    ExecutorService flushService = Executors.newSingleThreadExecutor();
    ExecutorService queueService = Executors.newSingleThreadExecutor();
    return new Dispatcher(context, mainThreadHandler, flushService, maxQueueSize, segmentHTTPApi,
        queue, queueService, stats);
  }

  Dispatcher(Context context, Handler mainThreadHandler, ExecutorService flushService,
      int maxQueueSize, SegmentHTTPApi segmentHTTPApi, ObjectQueue<BasePayload> queue,
      ExecutorService queueService, Stats stats) {
    this.context = context;
    this.mainThreadHandler = mainThreadHandler;
    this.maxQueueSize = maxQueueSize;
    this.segmentHTTPApi = segmentHTTPApi;
    this.queue = queue;
    this.flushService = flushService;
    this.queueService = queueService;
    this.stats = stats;
  }

  public void dispatchEnqueue(final BasePayload payload) {
    stats.dispatchEvent();
    queueService.submit(new Runnable() {
      @Override public void run() {
        enqueue(payload);
      }
    });
  }

  public void dispatchFlush() {
    flushService.submit(new Runnable() {
      @Override public void run() {
        flush();
      }
    });
  }

  private void enqueue(BasePayload payload) {
    queue.add(payload);
    Logger.d("Enqueued %s", payload);
    if (queue.size() >= maxQueueSize) {
      Logger.d("Queue size (%s) > maxQueueSize (%s)", queue.size(), maxQueueSize);
      dispatchFlush();
    }
  }

  private void flush() {
    if (queue.size() <= 0) {
      Logger.d("No events in queue, skipping flush.");
      return; // no-op
    }
    if (!isConnected(context)) {
      Logger.d("Not connected to network, skipping flush.");
      return;
    }

    stats.dispatchFlush();
    List<BasePayload> payloads = new ArrayList<BasePayload>();
    // This causes us to lose the guarantee that events will be delivered, since we could lose
    // power while adding them to the batch and the events would be lost from disk.
    while (queue.size() > 0) {
      BasePayload payload = queue.peek();
      payload.setSentAt(ISO8601Time.now());
      payloads.add(payload);
      queue.remove();
    }

    try {
      segmentHTTPApi.upload(payloads);
      Logger.d("Successfully uploaded %s payloads.", payloads.size());
    } catch (IOException e) {
      Logger.e(e, "Failed to upload payloads.");
      for (BasePayload payload : payloads) {
        queue.add(payload); // re-enqueue the payloads, don't trigger a flush again
      }
      Logger.d("Re-enqueued %s payloads.", payloads.size());
    }
  }
}
