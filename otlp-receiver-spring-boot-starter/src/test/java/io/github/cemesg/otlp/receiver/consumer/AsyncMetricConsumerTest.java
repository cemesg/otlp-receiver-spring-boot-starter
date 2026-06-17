package io.github.cemesg.otlp.receiver.consumer;

import io.github.cemesg.otlp.receiver.model.MetricPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncMetricConsumerTest {

    private static List<MetricPoint> batch(String name) {
        return List.of(new MetricPoint(name, null, "", "gauge", Instant.EPOCH, null,
                Map.of(), Map.of(), Map.of(), Map.of("value", 1)));
    }

    @Test
    void drainsBatchesToDelegate() throws Exception {
        CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch got = new CountDownLatch(2);
        MetricConsumer delegate = points -> { seen.add(points.get(0).name()); got.countDown(); };

        try (AsyncMetricConsumer async = new AsyncMetricConsumer(delegate, 100, 2)) {
            async.accept(batch("a"));
            async.accept(batch("b"));
            assertThat(got.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(seen).containsExactlyInAnyOrder("a", "b");
        }
    }

    @Test
    void signalsBackpressureWhenQueueFull() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MetricConsumer blocking = points -> {
            started.countDown();
            try {
                release.await();        // hold the single writer thread hostage
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // capacity 1, a single writer that we block -> queue fills, next accept must back off
        try (AsyncMetricConsumer async = new AsyncMetricConsumer(blocking, 1, 1)) {
            async.accept(batch("first"));                      // taken by the writer, which blocks
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            async.accept(batch("second"));                     // fills the 1-slot queue
            assertThatThrownBy(() -> async.accept(batch("third")))
                    .isInstanceOf(BackpressureException.class);
            release.countDown();
        }
    }
}
