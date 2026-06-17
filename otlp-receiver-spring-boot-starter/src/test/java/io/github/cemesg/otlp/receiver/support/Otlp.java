package io.github.cemesg.otlp.receiver.support;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogram;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;

import java.util.List;

/** Tiny builders for OTLP test payloads. Keeps the tests readable. */
public final class Otlp {

    public static final long START_NANOS = 1_700_000_000_000_000_000L;
    public static final long TIME_NANOS = 1_700_000_001_000_000_000L;

    private Otlp() {}

    public static KeyValue str(String k, String v) {
        return KeyValue.newBuilder().setKey(k).setValue(AnyValue.newBuilder().setStringValue(v)).build();
    }

    public static KeyValue i(String k, long v) {
        return KeyValue.newBuilder().setKey(k).setValue(AnyValue.newBuilder().setIntValue(v)).build();
    }

    public static ExportMetricsServiceRequest request(List<KeyValue> resourceAttrs, String scopeName, Metric... metrics) {
        ScopeMetrics.Builder scope = ScopeMetrics.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(scopeName).setVersion("1.0"));
        for (Metric m : metrics) scope.addMetrics(m);
        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder().addAllAttributes(resourceAttrs))
                        .addScopeMetrics(scope))
                .build();
    }

    public static Metric histogram(String name, String unit, List<KeyValue> pointAttrs) {
        HistogramDataPoint dp = HistogramDataPoint.newBuilder()
                .setStartTimeUnixNano(START_NANOS)
                .setTimeUnixNano(TIME_NANOS)
                .setCount(10).setSum(2.0).setMin(0.1).setMax(0.9)
                .addAllExplicitBounds(List.of(0.1, 0.5, 1.0))
                .addAllBucketCounts(List.of(2L, 5L, 2L, 1L))
                .addAllAttributes(pointAttrs)
                .build();
        return Metric.newBuilder().setName(name).setUnit(unit).setDescription("req duration")
                .setHistogram(Histogram.newBuilder()
                        .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                        .addDataPoints(dp))
                .build();
    }

    public static Metric gauge(String name, double value, List<KeyValue> pointAttrs) {
        NumberDataPoint dp = NumberDataPoint.newBuilder()
                .setTimeUnixNano(TIME_NANOS).setAsDouble(value).addAllAttributes(pointAttrs).build();
        return Metric.newBuilder().setName(name).setGauge(Gauge.newBuilder().addDataPoints(dp)).build();
    }

    public static Metric monotonicSum(String name, long value) {
        NumberDataPoint dp = NumberDataPoint.newBuilder()
                .setStartTimeUnixNano(START_NANOS).setTimeUnixNano(TIME_NANOS).setAsInt(value).build();
        return Metric.newBuilder().setName(name).setSum(Sum.newBuilder()
                .setIsMonotonic(true)
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                .addDataPoints(dp)).build();
    }

    public static Metric expHistogram(String name) {
        ExponentialHistogramDataPoint dp = ExponentialHistogramDataPoint.newBuilder()
                .setTimeUnixNano(TIME_NANOS).setCount(6).setSum(3.0).setScale(2).setZeroCount(1)
                .setPositive(ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .setOffset(1).addAllBucketCounts(List.of(2L, 3L)))
                .setNegative(ExponentialHistogramDataPoint.Buckets.newBuilder()
                        .setOffset(0).addAllBucketCounts(List.of(1L)))
                .build();
        return Metric.newBuilder().setName(name).setExponentialHistogram(ExponentialHistogram.newBuilder()
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA)
                .addDataPoints(dp)).build();
    }
}
