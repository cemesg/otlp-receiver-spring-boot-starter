package io.github.cemesg.otlp.receiver.normalize;

import io.github.cemesg.otlp.receiver.filter.MetricFilter;
import io.github.cemesg.otlp.receiver.model.MetricPoint;
import io.github.cemesg.otlp.receiver.model.MetricType;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Flattens the deeply-nested, polymorphic OTLP metrics structure into a flat
 * list of {@link MetricPoint} — one record per data point — hoisting metric
 * name/unit/description, instrumentation scope, and resource attributes down
 * onto each point. Lossless for the standard metric fields (start time, scope,
 * histogram min/max, exponential bucket layout are all preserved).
 * <p>
 * Filtering (see {@link MetricFilter}) is applied <b>in this single pass</b>:
 * dropped metric names/types are never materialized, and excluded keys are
 * skipped while attribute/source maps are built — so a kept point allocates its
 * maps exactly once. This is the hot path; it is deliberately sequential per
 * request (one OTLP request is already a batch — concurrency comes from handling
 * many requests at once, not from forking one).
 */
public class MetricNormalizer {

    /**
     * Canonical "who emitted this" resource keys, in display priority order.
     * Anything present is hoisted to the front of {@code source}; the rest of
     * the resource attributes follow so nothing is lost. Tuned for the
     * Kubernetes/OpenShift target: service identity, k8s topology, host, cloud.
     */
    static final List<String> IDENTITY_KEYS = List.of(
            "service.name", "service.namespace", "service.instance.id", "service.version",
            "k8s.cluster.name", "k8s.cluster.uid", "k8s.namespace.name",
            "k8s.deployment.name", "k8s.replicaset.name", "k8s.statefulset.name",
            "k8s.daemonset.name", "k8s.job.name", "k8s.cronjob.name",
            "k8s.pod.name", "k8s.pod.uid", "k8s.container.name", "k8s.node.name",
            "container.name", "container.id", "container.image.name", "container.image.tag",
            "host.name", "host.id", "host.arch",
            "cloud.provider", "cloud.region", "cloud.availability_zone", "cloud.account.id",
            "process.pid", "process.executable.name", "process.command",
            "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version",
            "telemetry.distro.name", "telemetry.distro.version", "os.type");

    private final MetricFilter filter;

    public MetricNormalizer(MetricFilter filter) {
        this.filter = filter;
    }

    public List<MetricPoint> normalize(ExportMetricsServiceRequest req) {
        List<MetricPoint> out = new ArrayList<>();
        for (ResourceMetrics rm : req.getResourceMetricsList()) {
            Map<String, Object> source = buildSource(rm.getResource().getAttributesList());
            for (ScopeMetrics sm : rm.getScopeMetricsList()) {
                Map<String, Object> scope = scope(sm.getScope());
                for (Metric m : sm.getMetricsList()) {
                    if (!filter.keepName(m.getName())) continue;   // whole metric dropped — never materialized
                    addPoints(m, source, scope, out);
                }
            }
        }
        return out;
    }

    /**
     * The emitting service/node identity: resource attributes reordered so the
     * canonical {@link #IDENTITY_KEYS} appear first, with everything else appended
     * in arrival order. Lossless by default; honors source-key filtering. Built
     * once per ResourceMetrics and shared by all its points (a cold path relative
     * to per-point work, so kept simple rather than micro-optimized).
     */
    private Map<String, Object> buildSource(List<KeyValue> resourceKvs) {
        Map<String, Object> remaining = attrs(resourceKvs, filter::keepSourceKey);
        Map<String, Object> source = new LinkedHashMap<>();
        for (String key : IDENTITY_KEYS) {           // identity keys first, in priority order
            Object v = remaining.remove(key);
            if (v != null) source.put(key, v);
        }
        source.putAll(remaining);                    // everything else, in arrival order
        return source;
    }

    private Map<String, Object> scope(InstrumentationScope scope) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (!scope.getName().isEmpty()) s.put("name", scope.getName());
        if (!scope.getVersion().isEmpty()) s.put("version", scope.getVersion());
        if (scope.getAttributesCount() > 0) s.put("attributes", attrs(scope.getAttributesList(), null));
        return s;
    }

    private void addPoints(Metric m, Map<String, Object> source, Map<String, Object> scope, List<MetricPoint> out) {
        switch (m.getDataCase()) {
            case GAUGE -> {
                if (!filter.keepType(MetricType.GAUGE)) return;
                for (NumberDataPoint dp : m.getGauge().getDataPointsList()) {
                    out.add(number(m, scope, dp, MetricType.GAUGE, source, null, null));
                }
            }
            case SUM -> {
                if (!filter.keepType(MetricType.SUM)) return;
                var sum = m.getSum();
                String temporality = temporality(sum.getAggregationTemporality());
                for (NumberDataPoint dp : sum.getDataPointsList()) {
                    out.add(number(m, scope, dp, MetricType.SUM, source, temporality, sum.getIsMonotonic()));
                }
            }
            case HISTOGRAM -> {
                if (!filter.keepType(MetricType.HISTOGRAM)) return;
                var h = m.getHistogram();
                String temporality = temporality(h.getAggregationTemporality());
                for (HistogramDataPoint dp : h.getDataPointsList()) {
                    out.add(histogram(m, scope, dp, source, temporality));
                }
            }
            case EXPONENTIAL_HISTOGRAM -> {
                if (!filter.keepType(MetricType.EXPONENTIAL_HISTOGRAM)) return;
                var eh = m.getExponentialHistogram();
                String temporality = temporality(eh.getAggregationTemporality());
                for (ExponentialHistogramDataPoint dp : eh.getDataPointsList()) {
                    out.add(exponentialHistogram(m, scope, dp, source, temporality));
                }
            }
            case SUMMARY -> {
                if (!filter.keepType(MetricType.SUMMARY)) return;
                for (SummaryDataPoint dp : m.getSummary().getDataPointsList()) {
                    out.add(summary(m, scope, dp, source));
                }
            }
            default -> { /* DATA_NOT_SET — nothing to emit */ }
        }
    }

    // ---- per-type builders --------------------------------------------------

    private MetricPoint number(Metric m, Map<String, Object> scope, NumberDataPoint dp, MetricType type,
                               Map<String, Object> source, String temporality, Boolean monotonic) {
        Object val = switch (dp.getValueCase()) {
            case AS_INT -> dp.getAsInt();
            case AS_DOUBLE -> dp.getAsDouble();
            default -> null;
        };
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("value", val);
        if (temporality != null) value.put("temporality", temporality);
        if (monotonic != null) value.put("monotonic", monotonic);
        return point(m, scope, dp.getStartTimeUnixNano(), dp.getTimeUnixNano(), type, source, dp.getAttributesList(), value);
    }

    private MetricPoint histogram(Metric m, Map<String, Object> scope, HistogramDataPoint dp,
                                  Map<String, Object> source, String temporality) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("count", dp.getCount());
        value.put("sum", dp.hasSum() ? dp.getSum() : null);
        if (dp.hasMin()) value.put("min", dp.getMin());
        if (dp.hasMax()) value.put("max", dp.getMax());
        value.put("bounds", dp.getExplicitBoundsList());
        value.put("buckets", dp.getBucketCountsList());
        value.put("temporality", temporality);
        if (dp.getCount() > 0 && dp.hasSum()) {
            value.put("average", dp.getSum() / dp.getCount());
        }
        return point(m, scope, dp.getStartTimeUnixNano(), dp.getTimeUnixNano(), MetricType.HISTOGRAM, source, dp.getAttributesList(), value);
    }

    private MetricPoint exponentialHistogram(Metric m, Map<String, Object> scope, ExponentialHistogramDataPoint dp,
                                             Map<String, Object> source, String temporality) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("count", dp.getCount());
        value.put("sum", dp.hasSum() ? dp.getSum() : null);
        value.put("scale", dp.getScale());
        value.put("zeroCount", dp.getZeroCount());
        value.put("zeroThreshold", dp.getZeroThreshold());
        value.put("positive", buckets(dp.getPositive()));
        value.put("negative", buckets(dp.getNegative()));
        if (dp.hasMin()) value.put("min", dp.getMin());
        if (dp.hasMax()) value.put("max", dp.getMax());
        value.put("temporality", temporality);
        return point(m, scope, dp.getStartTimeUnixNano(), dp.getTimeUnixNano(), MetricType.EXPONENTIAL_HISTOGRAM, source, dp.getAttributesList(), value);
    }

    /** Exponential-histogram bucket run: a starting index offset plus per-bucket counts. */
    private Map<String, Object> buckets(ExponentialHistogramDataPoint.Buckets b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offset", b.getOffset());
        out.put("counts", b.getBucketCountsList());
        return out;
    }

    private MetricPoint summary(Metric m, Map<String, Object> scope, SummaryDataPoint dp, Map<String, Object> source) {
        Map<String, Object> quantiles = new LinkedHashMap<>();
        for (SummaryDataPoint.ValueAtQuantile q : dp.getQuantileValuesList()) {
            quantiles.put(String.valueOf(q.getQuantile()), q.getValue());
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("count", dp.getCount());
        value.put("sum", dp.getSum());
        value.put("quantiles", quantiles);
        return point(m, scope, dp.getStartTimeUnixNano(), dp.getTimeUnixNano(), MetricType.SUMMARY, source, dp.getAttributesList(), value);
    }

    private MetricPoint point(Metric m, Map<String, Object> scope, long startUnixNano, long timeUnixNano,
                              MetricType type, Map<String, Object> source, List<KeyValue> pointAttrs, Map<String, Object> value) {
        return new MetricPoint(
                m.getName(),
                m.getDescription().isEmpty() ? null : m.getDescription(),
                m.getUnit(),
                type.wireName(),
                toInstant(timeUnixNano),
                startUnixNano == 0L ? null : toInstant(startUnixNano),  // 0 == unset
                scope,
                source,                                       // who emitted it (resource identity)
                attrs(pointAttrs, filter::keepAttributeKey),  // the metric's own dimensions
                value);
    }

    // ---- helpers ------------------------------------------------------------

    /** Build a flat attribute map, skipping keys the predicate rejects (null predicate = keep all). */
    private Map<String, Object> attrs(List<KeyValue> kvs, BiPredicate<String, Object> keep) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (KeyValue kv : kvs) {
            Object v = anyValue(kv.getValue());
            if (keep == null || keep.test(kv.getKey(), v)) out.put(kv.getKey(), v);
        }
        return out;
    }

    /** Resolve the OTLP AnyValue union into a plain Java value. */
    private Object anyValue(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case BOOL_VALUE -> v.getBoolValue();
            case INT_VALUE -> v.getIntValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case ARRAY_VALUE -> v.getArrayValue().getValuesList().stream().map(this::anyValue).toList();
            case KVLIST_VALUE -> attrs(v.getKvlistValue().getValuesList(), null);
            case BYTES_VALUE -> base64(v.getBytesValue());
            default -> null;
        };
    }

    private String base64(ByteString bytes) {
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private String temporality(AggregationTemporality t) {
        return switch (t) {
            case AGGREGATION_TEMPORALITY_DELTA -> "delta";
            case AGGREGATION_TEMPORALITY_CUMULATIVE -> "cumulative";
            default -> "unspecified";
        };
    }

    private Instant toInstant(long nanos) {
        return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    }
}
