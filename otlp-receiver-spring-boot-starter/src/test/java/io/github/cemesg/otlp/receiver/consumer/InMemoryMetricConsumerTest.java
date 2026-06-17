package io.github.cemesg.otlp.receiver.consumer;

import io.github.cemesg.otlp.receiver.model.MetricPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMetricConsumerTest {

    private static MetricPoint point(String name) {
        return new MetricPoint(name, null, "", "gauge", Instant.EPOCH, null,
                Map.of(), Map.of(), Map.of(), Map.of("value", 1));
    }

    @Test
    void snapshotReturnsAcceptedPointsInOrder() {
        InMemoryMetricConsumer c = new InMemoryMetricConsumer(10);
        c.accept(List.of(point("a"), point("b")));
        assertThat(c.snapshot()).extracting(MetricPoint::name).containsExactly("a", "b");
        assertThat(c.subscriberCount()).isZero();
    }

    @Test
    void ringBufferEvictsOldestBeyondCapacity() {
        InMemoryMetricConsumer c = new InMemoryMetricConsumer(3);
        c.accept(List.of(point("a"), point("b")));
        c.accept(List.of(point("c"), point("d")));   // total 4, cap 3 -> "a" evicted
        assertThat(c.snapshot()).extracting(MetricPoint::name).containsExactly("b", "c", "d");
    }
}
