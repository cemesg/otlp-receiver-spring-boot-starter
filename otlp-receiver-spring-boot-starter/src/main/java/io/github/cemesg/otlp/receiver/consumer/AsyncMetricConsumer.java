package io.github.cemesg.otlp.receiver.consumer;

import io.github.cemesg.otlp.receiver.model.MetricPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in {@link MetricConsumer} decorator implementing the <b>async ack-then-write</b>
 * strategy: {@code accept} enqueues a batch onto a bounded queue and returns
 * immediately, while a pool of writer threads drains it to a delegate consumer.
 * <p>
 * Not registered by default. To use it, declare a {@code MetricConsumer} bean
 * that wraps your real (durable) consumer:
 * <pre>
 * &#64;Bean MetricConsumer metricConsumer(MyKafkaConsumer delegate) {
 *     return new AsyncMetricConsumer(delegate, 10_000, 4); // capacity, writers
 * }
 * </pre>
 * When the queue is full it throws {@link BackpressureException}, so the ingest
 * controller returns 503 and the Collector retries — bounded memory, no silent
 * drops. (Trade-off: in-flight batches are lost on crash — at-most-once.)
 */
public class AsyncMetricConsumer implements MetricConsumer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncMetricConsumer.class);

    private final MetricConsumer delegate;
    private final BlockingQueue<List<MetricPoint>> queue;
    private final Thread[] writers;
    private volatile boolean running = true;

    public AsyncMetricConsumer(MetricConsumer delegate, int capacity, int writerThreads) {
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writers = new Thread[writerThreads];
        for (int i = 0; i < writerThreads; i++) {
            writers[i] = Thread.ofVirtual().name("otlp-writer-" + i).start(this::drain);
        }
    }

    @Override
    public void accept(List<MetricPoint> points) {
        if (points.isEmpty()) return;
        // Non-blocking offer: if full, signal backpressure instead of growing unbounded.
        if (!queue.offer(points)) {
            throw new BackpressureException("write queue full (" + queue.size() + ")");
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            List<MetricPoint> batch = null;
            try {
                batch = queue.poll(200, TimeUnit.MILLISECONDS);
                if (batch != null) delegate.accept(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException writeError) {
                // Keep the writer alive, but never fail silently: a real impl should
                // retry / dead-letter; the default logs and drops the batch.
                log.warn("Dropping {} metric point(s) after delegate write failure",
                        batch == null ? 0 : batch.size(), writeError);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        for (Thread w : writers) w.interrupt();
    }
}
