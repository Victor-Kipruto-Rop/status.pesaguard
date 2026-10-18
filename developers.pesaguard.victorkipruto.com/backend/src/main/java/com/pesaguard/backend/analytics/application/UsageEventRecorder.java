package com.pesaguard.backend.analytics.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.api.SafeExceptionDiagnostics;
import com.pesaguard.backend.analytics.domain.ApiRequestEvent;

/**
 * Buffers request events and hands them to a writer off the request thread.
 *
 * <p>Usage tracking must never be able to slow down or fail the request it is
 * measuring. A synchronous insert would add a database round trip to the latency
 * of every API call, and a momentary database problem would turn an analytics
 * outage into an outage of the payment API. So recording is enqueue-only on the
 * request thread and the write happens on a background thread.
 *
 * <p>The queue is <b>bounded</b>. An unbounded queue is not a safe default: under
 * sustained overload it grows until the heap is exhausted, and the resulting
 * {@code OutOfMemoryError} takes down the payment API — the exact outcome this
 * class exists to prevent.
 *
 * <p>When the queue is full the event is <b>dropped and counted</b>, never
 * silently discarded. {@link #droppedCount()} is exposed for monitoring; a
 * non-zero value means usage data is incomplete and someone should look at it.
 * Dropping a usage sample is strictly better than the alternatives: the request
 * still succeeds, and the loss is bounded, visible, and recoverable by
 * re-aggregation from application logs. Silently losing the data, or blocking
 * the caller, would both be worse.
 */
@Component
public class UsageEventRecorder implements DisposableBean, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UsageEventRecorder.class);

    private final BlockingQueue<ApiRequestEvent> queue;
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private final Thread worker;

    public UsageEventRecorder() {
        this(10_000, event -> { });
    }

    public UsageEventRecorder(int capacity, Consumer<ApiRequestEvent> sink) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.worker = new Thread(() -> drain(sink), "usage-recorder");
        // Daemon so a forgotten shutdown can never keep the JVM alive.
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Records an event. Never blocks and never throws.
     *
     * <p>Called on every request, so it must be cheap and incapable of affecting
     * the caller's outcome.
     *
     * @return true if buffered, false if dropped because the queue was full
     */
    public boolean record(ApiRequestEvent event) {
        if (event == null) {
            return false;
        }
        if (queue.offer(event)) {
            accepted.incrementAndGet();
            return true;
        }
        long total = dropped.incrementAndGet();
        // Log the first, then every 1000th, so an overload is visible without
        // turning the log into the bottleneck.
        if (total == 1 || total % 1000 == 0) {
            log.warn("usage event buffer full; dropped {} events so far. "
                    + "Usage reporting is incomplete until the writer catches up.", total);
        }
        return false;
    }

    private void drain(Consumer<ApiRequestEvent> sink) {
        List<ApiRequestEvent> batch = new ArrayList<>(256);
        while (running || !queue.isEmpty()) {
            try {
                // Blocking take with a timeout so the loop can observe shutdown.
                ApiRequestEvent first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                // Opportunistically drain whatever else is already queued, so a
                // burst is written in one pass instead of one row at a time.
                queue.drainTo(batch, 255);
                for (ApiRequestEvent event : batch) {
                    write(sink, event);
                }
                batch.clear();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                // The writer thread must survive anything the sink throws, or one
                // bad event would stop all subsequent recording.
                log.error("usage event writer failed; continuing type={} diagnostic={}",
                        failure.getClass().getName(), SafeExceptionDiagnostics.stackTrace(failure));
                batch.clear();
            }
        }
    }

    private void write(Consumer<ApiRequestEvent> sink, ApiRequestEvent event) {
        try {
            sink.accept(event);
        } catch (RuntimeException failure) {
            // One unwritable event must not stop the rest, and must not be lost
            // silently either.
            dropped.incrementAndGet();
            log.error("failed to persist usage event requestId={} type={} diagnostic={}",
                    event.getRequestId(), failure.getClass().getName(),
                    SafeExceptionDiagnostics.stackTrace(failure));
        }
    }

    /** Events successfully buffered. */
    public long acceptedCount() {
        return accepted.get();
    }

    /**
     * Events lost to a full buffer or a failed write.
     *
     * <p>Must be monitored. A rising value means usage data is incomplete.
     */
    public long droppedCount() {
        return dropped.get();
    }

    public int queueDepth() {
        return queue.size();
    }

    @Override
    public void destroy() {
        close();
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}