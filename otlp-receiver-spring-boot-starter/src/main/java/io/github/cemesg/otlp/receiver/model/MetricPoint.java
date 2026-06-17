package io.github.cemesg.otlp.receiver.model;

import java.time.Instant;
import java.util.Map;

/**
 * The clean, user-facing representation of a single metric data point — a
 * lossless, flat projection of one OTLP data point.
 * <p>
 * Decoupled from the OTLP wire types: numbers are real {@code long}/{@code double},
 * there is no protobuf {@code oneof} to decode, and attributes are flat maps.
 * <p>
 * Identity is separated from dimensions:
 * <ul>
 *   <li>{@code source} — <b>who emitted this</b>: OTLP <i>resource</i> attributes,
 *       ordered with the canonical service/k8s/host/cloud identity keys first.</li>
 *   <li>{@code scope} — the instrumentation scope ({@code name}/{@code version})
 *       that produced the metric.</li>
 *   <li>{@code attributes} — the metric's own dimensions (data-point attributes).</li>
 * </ul>
 * {@code startTimestamp} is the cumulative start time (null if unset) — needed to
 * compute rates and detect counter resets. The {@code value} map's shape depends
 * on {@code type}:
 * <ul>
 *   <li>gauge / sum: {@code {"value":.., "temporality":.., "monotonic":..}}</li>
 *   <li>histogram:   {@code {"count":.., "sum":.., "min":.., "max":.., "bounds":[..], "buckets":[..], ..}}</li>
 *   <li>exponential_histogram: {@code {"count":.., "sum":.., "scale":.., "zeroCount":.., "positive":{..}, "negative":{..}, "min":.., "max":..}}</li>
 *   <li>summary:     {@code {"count":.., "sum":.., "quantiles":{..}}}</li>
 * </ul>
 */
public record MetricPoint(
        String name,
        String description,
        String unit,
        String type,
        Instant timestamp,
        Instant startTimestamp,
        Map<String, Object> scope,
        Map<String, Object> source,
        Map<String, Object> attributes,
        Map<String, Object> value
) {}
