package io.github.cemesg.otlp.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Customization surface for the OTLP receiver — set under {@code otlp.receiver.*}
 * in {@code application.yml}. All filtering is glob-based ({@code *} wildcard),
 * so trimming what you don't need is quick and minimal, e.g.:
 *
 * <pre>
 * otlp:
 *   receiver:
 *     path: /v1/metrics
 *     drop-empty-values: true            # drop attrs/source entries with "" / null
 *     types: [sum, gauge, histogram]     # keep only these metric types (empty = all)
 *     metrics:
 *       exclude: ["system.network.*"]    # drop noisy metric names
 *     source:
 *       exclude: ["telemetry.distro.*", "host.id"]
 *     attributes:
 *       exclude: ["server.address", "server.port"]
 * </pre>
 */
@ConfigurationProperties(prefix = "otlp.receiver")
public class OtlpReceiverProperties {

    /** HTTP path the OTLP/HTTP ingest endpoint is mounted at. */
    private String path = "/v1/metrics";

    /** Metric types to keep ({@code gauge,sum,histogram,exponential_histogram,summary}). Empty = keep all. */
    private List<String> types = new ArrayList<>();

    /** Drop attribute/source entries whose value is null or an empty string. */
    private boolean dropEmptyValues = false;

    /** Include/exclude by metric name (glob). */
    @NestedConfigurationProperty
    private Globs metrics = new Globs();

    /** Include/exclude by resource/identity key (glob). */
    @NestedConfigurationProperty
    private Globs source = new Globs();

    /** Include/exclude by data-point attribute key (glob). */
    @NestedConfigurationProperty
    private Globs attributes = new Globs();

    /** An include/exclude pair of glob patterns. If {@code include} is non-empty, only matches pass. */
    public static class Globs {
        private List<String> include = new ArrayList<>();
        private List<String> exclude = new ArrayList<>();

        public List<String> getInclude() { return include; }
        public void setInclude(List<String> include) { this.include = include; }
        public List<String> getExclude() { return exclude; }
        public void setExclude(List<String> exclude) { this.exclude = exclude; }
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public List<String> getTypes() { return types; }
    public void setTypes(List<String> types) { this.types = types; }
    public boolean isDropEmptyValues() { return dropEmptyValues; }
    public void setDropEmptyValues(boolean dropEmptyValues) { this.dropEmptyValues = dropEmptyValues; }
    public Globs getMetrics() { return metrics; }
    public void setMetrics(Globs metrics) { this.metrics = metrics; }
    public Globs getSource() { return source; }
    public void setSource(Globs source) { this.source = source; }
    public Globs getAttributes() { return attributes; }
    public void setAttributes(Globs attributes) { this.attributes = attributes; }
}
