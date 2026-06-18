package io.github.cemesg.otlp.receiver.raw;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link RawMetricConsumer}: a bounded in-memory ring buffer of the most
 * recent raw payloads. Per-instance and non-durable — a dev/preview sink. For
 * production "store and process later", supply your own bean that writes the
 * bytes somewhere durable (Kafka, object store, file).
 */
public class InMemoryRawMetricConsumer implements RawMetricConsumer {

    private final int maxRecent;
    private final Deque<RawPayload> recent = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();

    public InMemoryRawMetricConsumer() {
        this(200);
    }

    public InMemoryRawMetricConsumer(int maxRecent) {
        this.maxRecent = maxRecent;
    }

    @Override
    public void accept(RawPayload payload) {
        recent.addLast(payload);
        if (size.incrementAndGet() > maxRecent) {
            if (recent.pollFirst() != null) size.decrementAndGet();
        }
    }

    public List<RawPayload> snapshot() {
        return new ArrayList<>(recent);
    }
}
