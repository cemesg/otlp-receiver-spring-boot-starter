package io.github.cemesg.otlp.receiver.web;

import io.github.cemesg.otlp.receiver.consumer.InMemoryMetricConsumer;
import io.github.cemesg.otlp.receiver.model.MetricPoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Optional read API over the {@link InMemoryMetricConsumer} default. Only active
 * when that consumer is in use (i.e. the app hasn't supplied its own
 * {@code MetricConsumer}). Provides a snapshot and a live SSE stream — handy for
 * a dev dashboard; not a horizontally-correct view across replicas.
 */
@RestController
public class MetricsViewController {

    private final InMemoryMetricConsumer store;

    public MetricsViewController(InMemoryMetricConsumer store) {
        this.store = store;
    }

    /** Snapshot of the most recent normalized points (e.g. for an initial page load). */
    @GetMapping("/api/metrics/recent")
    public List<MetricPoint> recent() {
        return store.snapshot();
    }

    /** Live stream of normalized points as they arrive (text/event-stream). */
    @GetMapping("/api/metrics/stream")
    public SseEmitter stream() {
        return store.subscribe();
    }
}
