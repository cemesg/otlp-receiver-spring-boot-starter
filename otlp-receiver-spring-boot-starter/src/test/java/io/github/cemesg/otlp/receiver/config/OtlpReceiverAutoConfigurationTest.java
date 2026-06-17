package io.github.cemesg.otlp.receiver.config;

import io.github.cemesg.otlp.receiver.consumer.InMemoryMetricConsumer;
import io.github.cemesg.otlp.receiver.consumer.MetricConsumer;
import io.github.cemesg.otlp.receiver.filter.MetricFilter;
import io.github.cemesg.otlp.receiver.model.MetricPoint;
import io.github.cemesg.otlp.receiver.normalize.MetricNormalizer;
import io.github.cemesg.otlp.receiver.web.MetricsViewController;
import io.github.cemesg.otlp.receiver.web.OtlpMetricsController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpReceiverAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtlpReceiverAutoConfiguration.class));

    @Test
    void wiresTheFullStackByDefault() {
        runner.run(ctx -> assertThat(ctx)
                .hasSingleBean(MetricFilter.class)
                .hasSingleBean(MetricNormalizer.class)
                .hasSingleBean(MetricConsumer.class)
                .hasSingleBean(InMemoryMetricConsumer.class)
                .hasSingleBean(OtlpMetricsController.class)
                .hasSingleBean(MetricsViewController.class));
    }

    @Test
    void userConsumerReplacesDefaultAndDisablesViewController() {
        runner.withUserConfiguration(CustomConsumerConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(MetricConsumer.class);
            assertThat(ctx.getBean(MetricConsumer.class)).isInstanceOf(CustomConsumer.class);
            assertThat(ctx).doesNotHaveBean(InMemoryMetricConsumer.class);   // backed off
            assertThat(ctx).doesNotHaveBean(MetricsViewController.class);     // needs the in-memory default
            assertThat(ctx).hasSingleBean(OtlpMetricsController.class);       // ingest still wired
        });
    }

    @Test
    void bindsConfigurationProperties() {
        runner.withPropertyValues("otlp.receiver.path=/ingest", "otlp.receiver.types=sum,gauge")
                .run(ctx -> {
                    OtlpReceiverProperties p = ctx.getBean(OtlpReceiverProperties.class);
                    assertThat(p.getPath()).isEqualTo("/ingest");
                    assertThat(p.getTypes()).containsExactly("sum", "gauge");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomConsumerConfig {
        @Bean
        MetricConsumer customConsumer() {
            return new CustomConsumer();
        }
    }

    static class CustomConsumer implements MetricConsumer {
        @Override
        public void accept(List<MetricPoint> points) { /* no-op */ }
    }
}
