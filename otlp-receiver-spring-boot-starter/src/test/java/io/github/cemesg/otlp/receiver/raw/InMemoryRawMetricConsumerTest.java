package io.github.cemesg.otlp.receiver.raw;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRawMetricConsumerTest {

    private static RawPayload payload(String body) {
        return new RawPayload(Instant.EPOCH, "application/json", null, body.getBytes());
    }

    @Test
    void snapshotKeepsAcceptedPayloadsInOrder() {
        InMemoryRawMetricConsumer c = new InMemoryRawMetricConsumer(10);
        c.accept(payload("a"));
        c.accept(payload("b"));
        assertThat(c.snapshot()).extracting(p -> new String(p.body())).containsExactly("a", "b");
    }

    @Test
    void ringBufferEvictsOldestBeyondCapacity() {
        InMemoryRawMetricConsumer c = new InMemoryRawMetricConsumer(2);
        c.accept(payload("a"));
        c.accept(payload("b"));
        c.accept(payload("c"));   // "a" evicted
        assertThat(c.snapshot()).extracting(p -> new String(p.body())).containsExactly("b", "c");
    }
}
