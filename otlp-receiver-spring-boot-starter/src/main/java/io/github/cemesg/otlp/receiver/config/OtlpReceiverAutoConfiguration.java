package io.github.cemesg.otlp.receiver.config;

import io.github.cemesg.otlp.receiver.consumer.InMemoryMetricConsumer;
import io.github.cemesg.otlp.receiver.consumer.MetricConsumer;
import io.github.cemesg.otlp.receiver.filter.MetricFilter;
import io.github.cemesg.otlp.receiver.normalize.MetricNormalizer;
import io.github.cemesg.otlp.receiver.web.MetricsViewController;
import io.github.cemesg.otlp.receiver.web.OtlpMetricsController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Drop-in OTLP metrics receiver for any Spring Boot app. Add the dependency and
 * it self-wires; everything is {@code @ConditionalOnMissingBean} so each piece
 * is overridable:
 * <ul>
 *   <li>{@link MetricFilter} / {@link MetricNormalizer} — OTLP → flat, filtered points.</li>
 *   <li>{@link OtlpMetricsController} — the ingest endpoint.</li>
 *   <li>{@link MetricConsumer} — the write target; defaults to
 *       {@link InMemoryMetricConsumer}. Define your own bean to replace it.</li>
 *   <li>{@link MetricsViewController} — dev read API, only when the in-memory
 *       default is in use.</li>
 * </ul>
 * Tune via {@code otlp.receiver.*} (see {@link OtlpReceiverProperties}).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)   // uses Spring MVC + SseEmitter
@EnableConfigurationProperties(OtlpReceiverProperties.class)
public class OtlpReceiverAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetricFilter metricFilter(OtlpReceiverProperties props) {
        return new MetricFilter(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricNormalizer metricNormalizer(MetricFilter filter) {
        return new MetricNormalizer(filter);
    }

    @Bean
    @ConditionalOnMissingBean(MetricConsumer.class)
    public InMemoryMetricConsumer inMemoryMetricConsumer() {
        return new InMemoryMetricConsumer();
    }

    @Bean
    @ConditionalOnMissingBean
    public OtlpMetricsController otlpMetricsController(MetricNormalizer normalizer, MetricConsumer consumer) {
        return new OtlpMetricsController(normalizer, consumer);
    }

    @Bean
    @ConditionalOnBean(InMemoryMetricConsumer.class)
    @ConditionalOnMissingBean
    public MetricsViewController metricsViewController(InMemoryMetricConsumer store) {
        return new MetricsViewController(store);
    }
}
