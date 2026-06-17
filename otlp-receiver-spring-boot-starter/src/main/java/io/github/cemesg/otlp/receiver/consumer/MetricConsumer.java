package io.github.cemesg.otlp.receiver.consumer;

import io.github.cemesg.otlp.receiver.model.MetricPoint;

import java.util.List;

/**
 * Where normalized, filtered metric points go. This is the extension point of
 * the receiver: any Spring app can define its own {@code MetricConsumer} bean
 * (write to Kafka, a DB, a websocket, …) and it replaces the built-in
 * {@link InMemoryMetricConsumer} default automatically (it is registered
 * {@code @ConditionalOnMissingBean(MetricConsumer.class)}).
 */
@FunctionalInterface
public interface MetricConsumer {
    void accept(List<MetricPoint> points);
}
