package io.github.cemesg.otlp.bench;

import io.github.cemesg.otlp.receiver.config.OtlpReceiverProperties;
import io.github.cemesg.otlp.receiver.filter.MetricFilter;
import io.github.cemesg.otlp.receiver.model.MetricPoint;
import io.github.cemesg.otlp.receiver.normalize.MetricNormalizer;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A/B for the three hot-path optimizations (type bitmask, exact-set key matching,
 * single-pass buildSource) — production normalizer vs the bench-only baseline.
 * <p>
 * Payloads are pre-built protobuf objects fed to {@code normalize()}, so this
 * isolates the RESHAPE logic (what changed) and excludes protobuf parsing (what
 * didn't). Two shapes: point-heavy (per-point key filtering dominates) and
 * resource-heavy (buildSource dominates).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class NormalizeBenchmark {

    private ExportMetricsServiceRequest pointHeavy;    // 4 resources x 10 metrics x 25 points = 1000 pts
    private ExportMetricsServiceRequest resourceHeavy; // 500 resources x 1 metric x 1 point = 500 pts
    private MetricNormalizer optimized;
    private BaselineNormalizer baseline;

    @Setup
    public void setup() {
        OtlpReceiverProperties props = new OtlpReceiverProperties();
        props.setDropEmptyValues(true);
        props.getSource().setExclude(List.of("telemetry.distro.*"));
        props.getAttributes().setExclude(List.of("server.address", "server.port"));

        optimized = new MetricNormalizer(new MetricFilter(props));
        baseline = new BaselineNormalizer(new BaselineFilter(props));

        pointHeavy = build(4, 10, 25);
        resourceHeavy = build(500, 1, 1);

        // Fail fast if the two implementations ever diverge (same key set per point).
        assertEquivalent(optimized.normalize(pointHeavy), baseline.normalize(pointHeavy));
        assertEquivalent(optimized.normalize(resourceHeavy), baseline.normalize(resourceHeavy));
    }

    @Benchmark public void pointHeavy_optimized(Blackhole bh) { bh.consume(optimized.normalize(pointHeavy)); }
    @Benchmark public void pointHeavy_baseline(Blackhole bh)  { bh.consume(baseline.normalize(pointHeavy)); }
    @Benchmark public void resourceHeavy_optimized(Blackhole bh) { bh.consume(optimized.normalize(resourceHeavy)); }
    @Benchmark public void resourceHeavy_baseline(Blackhole bh)  { bh.consume(baseline.normalize(resourceHeavy)); }

    // ---- payload construction ----------------------------------------------

    private static ExportMetricsServiceRequest build(int resources, int metricsPer, int pointsPer) {
        ExportMetricsServiceRequest.Builder req = ExportMetricsServiceRequest.newBuilder();
        for (int r = 0; r < resources; r++) {
            ScopeMetrics.Builder scope = ScopeMetrics.newBuilder()
                    .setScope(InstrumentationScope.newBuilder().setName("go.opentelemetry.io/obi").setVersion("1.0"));
            for (int mi = 0; mi < metricsPer; mi++) {
                scope.addMetrics(histogramMetric(pointsPer));
            }
            req.addResourceMetrics(ResourceMetrics.newBuilder()
                    .setResource(Resource.newBuilder().addAllAttributes(resourceAttrs(r)))
                    .addScopeMetrics(scope));
        }
        return req.build();
    }

    /** ~23 resource attributes: identity (reordered) + excluded globs + empty (dropped) + overflow. */
    private static List<KeyValue> resourceAttrs(int r) {
        return List.of(
                str("service.name", "sampleapp"),
                str("service.namespace", "storefront"),
                str("service.instance.id", "pod-" + r),
                str("service.version", "1.4.2"),
                str("k8s.cluster.name", "ocp-prod"),
                str("k8s.namespace.name", "storefront"),
                str("k8s.deployment.name", "sampleapp"),
                str("k8s.pod.name", "sampleapp-7d9f9c-" + r),
                str("k8s.node.name", "worker-node-01"),
                str("k8s.container.name", "app"),
                str("container.id", "c" + r),
                str("container.image.name", "registry/sampleapp"),
                str("host.name", "node-" + r),
                str("host.id", ""),                          // empty -> dropped by drop-empty-values
                str("host.arch", "amd64"),
                str("telemetry.sdk.name", "beyla"),
                str("telemetry.sdk.language", "go"),
                str("telemetry.sdk.version", "1.43.0"),
                str("telemetry.distro.name", "obi"),         // excluded by telemetry.distro.*
                str("telemetry.distro.version", "unset"),    // excluded by telemetry.distro.*
                str("os.type", "linux"),
                str("deployment.environment", "production"),  // overflow (non-identity)
                str("custom.team", "checkout"));              // overflow (non-identity)
    }

    private static Metric histogramMetric(int points) {
        Histogram.Builder h = Histogram.newBuilder()
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE);
        for (int i = 0; i < points; i++) {
            h.addDataPoints(HistogramDataPoint.newBuilder()
                    .setStartTimeUnixNano(1_700_000_000_000_000_000L)
                    .setTimeUnixNano(1_700_000_001_000_000_000L)
                    .setCount(150 + i).setSum(9.45 + i).setMin(0.001).setMax(2.5)
                    .addAllExplicitBounds(List.of(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0))
                    .addAllBucketCounts(List.of(5L, 12L, 30L, 44L, 60L, 88L, 120L, 140L, 150L))
                    .addAllAttributes(pointAttrs(i)));
        }
        return Metric.newBuilder().setName("http.server.request.duration").setUnit("s").setHistogram(h).build();
    }

    /** 7 data-point attributes: kept dimensions + 2 excluded + an int. */
    private static List<KeyValue> pointAttrs(int i) {
        return List.of(
                str("http.request.method", (i % 2 == 0) ? "GET" : "POST"),
                str("http.route", "/api/v1/resource/{id}"),
                intkv("http.response.status_code", (i % 5 == 0) ? 404 : 200),
                str("server.address", "sampleapp"),     // excluded
                intkv("server.port", 8080),             // excluded
                str("url.scheme", "http"),
                str("network.peer.address", "10.0.0." + (i % 255)));
    }

    private static KeyValue str(String k, String v) {
        return KeyValue.newBuilder().setKey(k).setValue(AnyValue.newBuilder().setStringValue(v)).build();
    }

    private static KeyValue intkv(String k, long v) {
        return KeyValue.newBuilder().setKey(k).setValue(AnyValue.newBuilder().setIntValue(v)).build();
    }

    private static void assertEquivalent(List<MetricPoint> a, List<MetricPoint> b) {
        if (a.size() != b.size()) throw new AssertionError("size " + a.size() + " != " + b.size());
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).source().equals(b.get(i).source())
                    || !a.get(i).attributes().equals(b.get(i).attributes())) {
                throw new AssertionError("divergence at point " + i + "\n opt=" + a.get(i) + "\n base=" + b.get(i));
            }
        }
    }
}
