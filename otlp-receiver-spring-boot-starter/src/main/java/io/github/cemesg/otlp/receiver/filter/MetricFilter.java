package io.github.cemesg.otlp.receiver.filter;

import io.github.cemesg.otlp.receiver.config.OtlpReceiverProperties;
import io.github.cemesg.otlp.receiver.model.MetricType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Glob-based include/exclude predicates derived from {@link OtlpReceiverProperties},
 * exposed as cheap checks so the normalizer can apply them <b>during</b> the OTLP
 * walk (filtered-out metrics are never materialized; dropped keys are skipped
 * while maps are built).
 * <p>
 * Hot-path shape:
 * <ul>
 *   <li><b>Types</b> use an {@link EnumSet} — {@link #keepType(MetricType)} is an
 *       O(1) bitset lookup, no string compare.</li>
 *   <li><b>Keys</b> are matched against an O(1) {@link Set} for exact patterns
 *       (the common case) and only fall back to regex for patterns containing
 *       {@code *}. Avoids running a regex per attribute key per data point.</li>
 *   <li>When nothing is configured for a dimension, its check short-circuits on a
 *       boolean flag — zero per-point cost.</li>
 * </ul>
 */
public class MetricFilter {

    private final Set<MetricType> types;
    private final KeySet name;
    private final KeySet source;
    private final KeySet attr;

    public MetricFilter(OtlpReceiverProperties props) {
        this.types = parseTypes(props.getTypes());
        boolean dropEmpty = props.isDropEmptyValues();
        // Names have no value, so dropEmpty doesn't apply to them.
        this.name = new KeySet(props.getMetrics().getInclude(), props.getMetrics().getExclude(), false);
        this.source = new KeySet(props.getSource().getInclude(), props.getSource().getExclude(), dropEmpty);
        this.attr = new KeySet(props.getAttributes().getInclude(), props.getAttributes().getExclude(), dropEmpty);
    }

    public boolean keepType(MetricType type) {
        return types.contains(type);
    }

    public boolean keepName(String metricName) {
        return name.keep(metricName, null);
    }

    public boolean keepSourceKey(String key, Object value) {
        return source.keep(key, value);
    }

    public boolean keepAttributeKey(String key, Object value) {
        return attr.keep(key, value);
    }

    // ---- internals ----------------------------------------------------------

    /** Empty config means keep every type; unknown names are ignored. */
    private static Set<MetricType> parseTypes(List<String> names) {
        if (names.isEmpty()) return EnumSet.allOf(MetricType.class);
        Set<MetricType> set = EnumSet.noneOf(MetricType.class);
        for (String n : names) {
            MetricType t = MetricType.fromWireName(n.trim());
            if (t != null) set.add(t);
        }
        return set;
    }

    /**
     * Include/exclude matcher split into exact (hash-set, O(1)) and glob (regex)
     * tiers so the common all-exact config never touches a regex engine.
     */
    private static final class KeySet {
        private final Set<String> exactInclude;
        private final Set<String> exactExclude;
        private final List<Pattern> globInclude;
        private final List<Pattern> globExclude;
        private final boolean includeActive;
        private final boolean dropEmpty;
        private final boolean active;

        KeySet(List<String> include, List<String> exclude, boolean dropEmpty) {
            this.exactInclude = new HashSet<>();
            this.exactExclude = new HashSet<>();
            this.globInclude = new ArrayList<>();
            this.globExclude = new ArrayList<>();
            split(include, exactInclude, globInclude);
            split(exclude, exactExclude, globExclude);
            this.includeActive = !exactInclude.isEmpty() || !globInclude.isEmpty();
            this.dropEmpty = dropEmpty;
            this.active = includeActive || !exactExclude.isEmpty() || !globExclude.isEmpty() || dropEmpty;
        }

        boolean keep(String key, Object value) {
            if (!active) return true;                                   // fast path: nothing configured
            if (dropEmpty && (value == null || "".equals(value))) return false;
            if (includeActive && !matches(key, exactInclude, globInclude)) return false;
            return !matches(key, exactExclude, globExclude);
        }

        private static boolean matches(String key, Set<String> exact, List<Pattern> globs) {
            if (exact.contains(key)) return true;
            for (Pattern p : globs) {
                if (p.matcher(key).matches()) return true;
            }
            return false;
        }

        private static void split(List<String> patterns, Set<String> exact, List<Pattern> globs) {
            for (String p : patterns) {
                if (p.indexOf('*') < 0) exact.add(p);     // no wildcard -> exact match
                else globs.add(glob(p));
            }
        }

        private static Pattern glob(String pattern) {
            StringBuilder re = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') re.append(".*");
                else re.append(Pattern.quote(String.valueOf(c)));
            }
            return Pattern.compile(re.append('$').toString());
        }
    }
}
