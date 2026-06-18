package io.github.cemesg.otlp.receiver.raw;

/**
 * Where captured-but-undecoded OTLP payloads go in {@code raw} mode. The
 * extension point for "store now, process later": provide your own bean (write
 * the bytes to Kafka, S3, a file, …) and it replaces {@link InMemoryRawMetricConsumer}
 * via {@code @ConditionalOnMissingBean(RawMetricConsumer.class)}.
 */
@FunctionalInterface
public interface RawMetricConsumer {
    void accept(RawPayload payload);
}
