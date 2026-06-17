package io.github.cemesg.otlp.receiver.model;

/**
 * The OTLP metric data-point kinds, with their canonical lowercase wire names
 * (as they appear in {@link MetricPoint#type()} and in {@code otlp.receiver.types}
 * configuration). Single source of truth for those names.
 */
public enum MetricType {
    GAUGE("gauge"),
    SUM("sum"),
    HISTOGRAM("histogram"),
    EXPONENTIAL_HISTOGRAM("exponential_histogram"),
    SUMMARY("summary");

    private final String wireName;

    MetricType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** The type for a wire name, or {@code null} if unrecognized. */
    public static MetricType fromWireName(String name) {
        for (MetricType t : values()) {
            if (t.wireName.equals(name)) return t;
        }
        return null;
    }
}
