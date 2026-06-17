package io.github.cemesg.otlp.bench;

import io.github.cemesg.otlp.receiver.config.OtlpReceiverProperties;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BENCH-ONLY baseline: the receiver's filter <i>before</i> the optimizations —
 * every pattern (even exact keys) is a compiled regex matched per key, and types
 * are a {@code Set<String>} checked by name. Used purely to A/B against the
 * production {@link io.github.cemesg.otlp.receiver.filter.MetricFilter}. Not shipped.
 */
public class BaselineFilter {

    private final List<Pattern> nameInclude, nameExclude;
    private final List<Pattern> sourceInclude, sourceExclude;
    private final List<Pattern> attrInclude, attrExclude;
    private final Set<String> types;
    private final boolean dropEmpty;
    private final boolean nameActive, sourceActive, attrActive, typeActive;

    public BaselineFilter(OtlpReceiverProperties props) {
        this.nameInclude = compile(props.getMetrics().getInclude());
        this.nameExclude = compile(props.getMetrics().getExclude());
        this.sourceInclude = compile(props.getSource().getInclude());
        this.sourceExclude = compile(props.getSource().getExclude());
        this.attrInclude = compile(props.getAttributes().getInclude());
        this.attrExclude = compile(props.getAttributes().getExclude());
        this.types = props.getTypes().stream().map(String::trim).collect(Collectors.toSet());
        this.dropEmpty = props.isDropEmptyValues();
        this.nameActive = !nameInclude.isEmpty() || !nameExclude.isEmpty();
        this.sourceActive = !sourceInclude.isEmpty() || !sourceExclude.isEmpty() || dropEmpty;
        this.attrActive = !attrInclude.isEmpty() || !attrExclude.isEmpty() || dropEmpty;
        this.typeActive = !types.isEmpty();
    }

    public boolean keepName(String name) {
        if (!nameActive) return true;
        return matchesIncludeExclude(name, nameInclude, nameExclude);
    }

    public boolean keepType(String type) {
        return !typeActive || types.contains(type);
    }

    public boolean keepSourceKey(String key, Object value) {
        if (!sourceActive) return true;
        if (dropEmpty && isEmpty(value)) return false;
        return matchesIncludeExclude(key, sourceInclude, sourceExclude);
    }

    public boolean keepAttributeKey(String key, Object value) {
        if (!attrActive) return true;
        if (dropEmpty && isEmpty(value)) return false;
        return matchesIncludeExclude(key, attrInclude, attrExclude);
    }

    private static boolean matchesIncludeExclude(String s, List<Pattern> include, List<Pattern> exclude) {
        if (!include.isEmpty() && include.stream().noneMatch(p -> p.matcher(s).matches())) return false;
        return exclude.stream().noneMatch(p -> p.matcher(s).matches());
    }

    private static boolean isEmpty(Object v) {
        return v == null || "".equals(v);
    }

    private static List<Pattern> compile(List<String> globs) {
        return globs.stream().map(BaselineFilter::glob).toList();
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
