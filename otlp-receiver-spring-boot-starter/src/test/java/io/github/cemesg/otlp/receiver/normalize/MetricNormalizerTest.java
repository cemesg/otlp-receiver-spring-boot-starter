package io.github.cemesg.otlp.receiver.normalize;

import io.github.cemesg.otlp.receiver.config.OtlpReceiverProperties;
import io.github.cemesg.otlp.receiver.filter.MetricFilter;
import io.github.cemesg.otlp.receiver.model.MetricPoint;
import io.github.cemesg.otlp.receiver.support.Otlp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricNormalizerTest {

    private static MetricNormalizer normalizer(OtlpReceiverProperties props) {
        return new MetricNormalizer(new MetricFilter(props));
    }

    private static MetricNormalizer passthrough() {
        return normalizer(new OtlpReceiverProperties());
    }

    @Test
    void sourceOrdersIdentityKeysFirstThenOverflowLossless() {
        var req = Otlp.request(
                List.of(
                        Otlp.str("custom.team", "checkout"),     // overflow (non-identity)
                        Otlp.str("k8s.pod.name", "pod-1"),       // identity (later priority)
                        Otlp.str("service.name", "checkout")),   // identity (first priority)
                "scope",
                Otlp.gauge("g", 1.0, List.of()));

        MetricPoint p = passthrough().normalize(req).get(0);

        // identity keys hoisted in priority order, overflow appended, nothing lost
        assertThat(p.source().keySet()).containsExactly("service.name", "k8s.pod.name", "custom.team");
        assertThat(p.source()).containsEntry("service.name", "checkout").containsEntry("custom.team", "checkout");
    }

    @Test
    void histogramIsLosslessAcrossStartTimeScopeMinMaxBucketsDescription() {
        var req = Otlp.request(
                List.of(Otlp.str("service.name", "svc")),
                "go.opentelemetry.io/obi",
                Otlp.histogram("http.server.request.duration", "s",
                        List.of(Otlp.str("http.route", "/x"), Otlp.i("http.response.status_code", 200))));

        MetricPoint p = passthrough().normalize(req).get(0);

        assertThat(p.type()).isEqualTo("histogram");
        assertThat(p.unit()).isEqualTo("s");
        assertThat(p.description()).isEqualTo("req duration");
        assertThat(p.timestamp()).isEqualTo(instant(Otlp.TIME_NANOS));
        assertThat(p.startTimestamp()).isEqualTo(instant(Otlp.START_NANOS));
        assertThat(p.scope()).containsEntry("name", "go.opentelemetry.io/obi").containsEntry("version", "1.0");
        assertThat(p.value())
                .containsEntry("count", 10L)
                .containsEntry("sum", 2.0)
                .containsEntry("min", 0.1)
                .containsEntry("max", 0.9)
                .containsEntry("temporality", "cumulative");
        assertThat(p.value().get("bounds")).isEqualTo(List.of(0.1, 0.5, 1.0));
        assertThat(p.value().get("buckets")).isEqualTo(List.of(2L, 5L, 2L, 1L));
        // dimensions live in attributes, not source
        assertThat(p.attributes()).containsEntry("http.route", "/x").containsEntry("http.response.status_code", 200L);
        assertThat(p.attributes()).doesNotContainKey("service.name");
    }

    @Test
    void gaugeAndMonotonicSumValues() {
        var gauge = passthrough().normalize(
                Otlp.request(List.of(), "s", Otlp.gauge("cpu", 0.42, List.of()))).get(0);
        assertThat(gauge.type()).isEqualTo("gauge");
        assertThat(gauge.value()).containsEntry("value", 0.42);
        assertThat(gauge.startTimestamp()).isNull();   // gauge has no start time

        var sum = passthrough().normalize(
                Otlp.request(List.of(), "s", Otlp.monotonicSum("requests", 7))).get(0);
        assertThat(sum.type()).isEqualTo("sum");
        assertThat(sum.value()).containsEntry("value", 7L)
                .containsEntry("monotonic", true)
                .containsEntry("temporality", "cumulative");
    }

    @Test
    void exponentialHistogramPreservesBucketLayout() {
        var p = passthrough().normalize(
                Otlp.request(List.of(), "s", Otlp.expHistogram("lat"))).get(0);
        assertThat(p.type()).isEqualTo("exponential_histogram");
        assertThat(p.value()).containsEntry("scale", 2).containsEntry("zeroCount", 1L)
                .containsEntry("temporality", "delta");
        @SuppressWarnings("unchecked")
        var positive = (java.util.Map<String, Object>) p.value().get("positive");
        assertThat(positive).containsEntry("offset", 1).containsEntry("counts", List.of(2L, 3L));
    }

    @Test
    void filteringIsAppliedDuringNormalization() {
        OtlpReceiverProperties props = new OtlpReceiverProperties();
        props.setDropEmptyValues(true);
        props.getSource().setExclude(List.of("telemetry.distro.*"));
        props.getAttributes().setExclude(List.of("server.address"));
        props.getMetrics().setExclude(List.of("system.*"));

        var req = Otlp.request(
                List.of(
                        Otlp.str("service.name", "svc"),
                        Otlp.str("host.id", ""),                       // dropped: empty
                        Otlp.str("telemetry.distro.name", "obi")),     // dropped: glob
                "scope",
                Otlp.histogram("http.server.request.duration", "s",
                        List.of(Otlp.str("http.route", "/x"), Otlp.str("server.address", "h"))),
                Otlp.gauge("system.cpu.time", 1.0, List.of()));        // dropped: name glob

        var points = normalizer(props).normalize(req);

        assertThat(points).hasSize(1);                                  // system.* metric removed entirely
        MetricPoint p = points.get(0);
        assertThat(p.source()).containsKey("service.name")
                .doesNotContainKey("host.id")
                .doesNotContainKey("telemetry.distro.name");
        assertThat(p.attributes()).containsKey("http.route").doesNotContainKey("server.address");
    }

    private static Instant instant(long nanos) {
        return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    }
}
