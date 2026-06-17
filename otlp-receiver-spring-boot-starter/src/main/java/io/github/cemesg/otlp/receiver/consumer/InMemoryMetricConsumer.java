package io.github.cemesg.otlp.receiver.consumer;

import io.github.cemesg.otlp.receiver.model.MetricPoint;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link MetricConsumer}: a lock-free in-memory ring buffer of the most
 * recent points plus live SSE fan-out. This is the synchronous write-then-ack
 * default — {@code accept} stores and returns, so ingest acks immediately.
 * <p>
 * It is per-instance (not shared across replicas) and non-durable (lost on
 * restart) — ideal as a dev/preview sink. For production, provide your own
 * {@code MetricConsumer} bean (Kafka, TSDB, OTLP-forward); it replaces this
 * automatically via {@code @ConditionalOnMissingBean(MetricConsumer.class)}.
 */
public class InMemoryMetricConsumer implements MetricConsumer {

    private final int maxRecent;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Deque<MetricPoint> recent = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();

    public InMemoryMetricConsumer() {
        this(500);
    }

    public InMemoryMetricConsumer(int maxRecent) {
        this.maxRecent = maxRecent;
    }

    @Override
    public void accept(List<MetricPoint> points) {
        for (MetricPoint p : points) {
            recent.addLast(p);
            if (size.incrementAndGet() > maxRecent) {
                if (recent.pollFirst() != null) size.decrementAndGet();
            }
        }
        for (SseEmitter emitter : emitters) {
            try {
                for (MetricPoint p : points) {
                    emitter.send(SseEmitter.event().data(p, MediaType.APPLICATION_JSON));
                }
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter); // client gone
            }
        }
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // never time out
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public List<MetricPoint> snapshot() {
        return new ArrayList<>(recent);
    }

    /** Number of live SSE subscribers — exposed for tests/metrics. */
    public int subscriberCount() {
        return emitters.size();
    }
}
