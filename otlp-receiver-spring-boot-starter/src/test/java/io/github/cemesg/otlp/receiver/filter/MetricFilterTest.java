package io.github.cemesg.otlp.receiver.filter;

import io.github.cemesg.otlp.receiver.config.OtlpReceiverProperties;
import io.github.cemesg.otlp.receiver.model.MetricType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricFilterTest {

    private static MetricFilter filter(OtlpReceiverProperties p) {
        return new MetricFilter(p);
    }

    @Test
    void keepsEverythingWhenUnconfigured() {
        MetricFilter f = filter(new OtlpReceiverProperties());
        assertThat(f.keepType(MetricType.GAUGE)).isTrue();
        assertThat(f.keepType(MetricType.SUMMARY)).isTrue();
        assertThat(f.keepName("anything")).isTrue();
        assertThat(f.keepSourceKey("k8s.pod.name", "p")).isTrue();
        assertThat(f.keepAttributeKey("http.route", "/x")).isTrue();
        assertThat(f.keepSourceKey("host.id", "")).isTrue();   // dropEmpty off -> kept
    }

    @Test
    void typeFilterKeepsOnlyConfiguredTypes() {
        OtlpReceiverProperties p = new OtlpReceiverProperties();
        p.setTypes(List.of("sum", "gauge"));
        MetricFilter f = filter(p);
        assertThat(f.keepType(MetricType.SUM)).isTrue();
        assertThat(f.keepType(MetricType.GAUGE)).isTrue();
        assertThat(f.keepType(MetricType.HISTOGRAM)).isFalse();
        assertThat(f.keepType(MetricType.EXPONENTIAL_HISTOGRAM)).isFalse();
        assertThat(f.keepType(MetricType.SUMMARY)).isFalse();
    }

    @Test
    void nameExcludeSupportsExactAndGlob() {
        OtlpReceiverProperties p = new OtlpReceiverProperties();
        p.getMetrics().setExclude(List.of("system.network.*", "exact.drop"));
        MetricFilter f = filter(p);
        assertThat(f.keepName("system.network.io")).isFalse();   // glob
        assertThat(f.keepName("exact.drop")).isFalse();          // exact
        assertThat(f.keepName("system.cpu.time")).isTrue();
        assertThat(f.keepName("http.server.request.duration")).isTrue();
    }

    @Test
    void includeRestrictsToMatches() {
        OtlpReceiverProperties p = new OtlpReceiverProperties();
        p.getSource().setInclude(List.of("service.*", "k8s.namespace.name"));
        MetricFilter f = filter(p);
        assertThat(f.keepSourceKey("service.name", "x")).isTrue();
        assertThat(f.keepSourceKey("k8s.namespace.name", "x")).isTrue();
        assertThat(f.keepSourceKey("host.name", "x")).isFalse();  // not included
    }

    @Test
    void dropEmptyValuesDropsBlankAndNull() {
        OtlpReceiverProperties p = new OtlpReceiverProperties();
        p.setDropEmptyValues(true);
        MetricFilter f = filter(p);
        assertThat(f.keepAttributeKey("k", "")).isFalse();
        assertThat(f.keepAttributeKey("k", null)).isFalse();
        assertThat(f.keepAttributeKey("k", "v")).isTrue();
        assertThat(f.keepSourceKey("host.id", "")).isFalse();
    }

    @Test
    void excludeWinsAndGlobOnAttributes() {
        OtlpReceiverProperties p = new OtlpReceiverProperties();
        p.getAttributes().setExclude(List.of("server.*"));
        MetricFilter f = filter(p);
        assertThat(f.keepAttributeKey("server.address", "x")).isFalse();
        assertThat(f.keepAttributeKey("server.port", 8080)).isFalse();
        assertThat(f.keepAttributeKey("http.route", "/x")).isTrue();
    }
}
