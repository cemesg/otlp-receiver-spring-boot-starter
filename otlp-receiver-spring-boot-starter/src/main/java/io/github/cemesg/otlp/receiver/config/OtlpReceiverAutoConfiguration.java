package io.github.cemesg.otlp.receiver.config;

import io.github.cemesg.otlp.receiver.consumer.InMemoryMetricConsumer;
import io.github.cemesg.otlp.receiver.consumer.MetricConsumer;
import io.github.cemesg.otlp.receiver.filter.MetricFilter;
import io.github.cemesg.otlp.receiver.normalize.MetricNormalizer;
import io.github.cemesg.otlp.receiver.raw.InMemoryRawMetricConsumer;
import io.github.cemesg.otlp.receiver.raw.RawMetricConsumer;
import io.github.cemesg.otlp.receiver.raw.RawMetricsController;
import io.github.cemesg.otlp.receiver.raw.RawOtlpController;
import io.github.cemesg.otlp.receiver.web.MetricsViewController;
import io.github.cemesg.otlp.receiver.web.OtlpMetricsController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Drop-in OTLP metrics receiver for any Spring Boot app. Add the dependency and
 * it self-wires; everything is {@code @ConditionalOnMissingBean} so each piece
 * is overridable. The active stack is chosen by {@code otlp.receiver.mode}:
 * <ul>
 *   <li><b>normalize</b> (default) — decode OTLP → flat per-point JSON via
 *       {@link MetricNormalizer}, served on {@code /api/metrics/*}. Replace the
 *       write target by defining your own {@link MetricConsumer} bean.</li>
 *   <li><b>raw</b> — store the OTLP body verbatim via a {@link RawMetricConsumer}
 *       (default {@link InMemoryRawMetricConsumer}) and serve it on
 *       {@code /api/raw/*}. No decode/normalize; process the bytes later.</li>
 * </ul>
 * Both ingest on {@code ${otlp.receiver.path:/v1/metrics}}; only one is active.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)   // uses Spring MVC + SseEmitter
@EnableConfigurationProperties(OtlpReceiverProperties.class)
public class OtlpReceiverAutoConfiguration {

    /** Default: decode and normalize OTLP into flat per-point JSON. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "otlp.receiver.mode", havingValue = "normalize", matchIfMissing = true)
    static class NormalizeMode {

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

    /** Opt-in: store the OTLP body as-is, process later. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "otlp.receiver.mode", havingValue = "raw")
    static class RawMode {

        @Bean
        @ConditionalOnMissingBean(RawMetricConsumer.class)
        public InMemoryRawMetricConsumer inMemoryRawMetricConsumer() {
            return new InMemoryRawMetricConsumer();
        }

        @Bean
        @ConditionalOnMissingBean
        public RawOtlpController rawOtlpController(RawMetricConsumer consumer) {
            return new RawOtlpController(consumer);
        }

        @Bean
        @ConditionalOnBean(InMemoryRawMetricConsumer.class)
        @ConditionalOnMissingBean
        public RawMetricsController rawMetricsController(InMemoryRawMetricConsumer store) {
            return new RawMetricsController(store);
        }
    }
}
