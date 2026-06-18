package io.github.cemesg.otlp.receiver.raw;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read API for {@code raw} mode: the most recently captured OTLP payloads, as
 * stored (undecoded). Active only when {@link InMemoryRawMetricConsumer} is in
 * use. Plain-JSON bodies are returned as text for convenience; gzipped or
 * protobuf bodies are base64-encoded so the raw bytes survive the round-trip.
 */
@RestController
public class RawMetricsController {

    private final InMemoryRawMetricConsumer store;

    public RawMetricsController(InMemoryRawMetricConsumer store) {
        this.store = store;
    }

    @GetMapping("/api/raw/recent")
    public List<Map<String, Object>> recent() {
        return store.snapshot().stream().map(RawMetricsController::view).toList();
    }

    private static Map<String, Object> view(RawPayload p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receivedAt", p.receivedAt());
        out.put("contentType", p.contentType());
        out.put("contentEncoding", p.contentEncoding());
        out.put("sizeBytes", p.sizeBytes());
        boolean gzip = p.contentEncoding() != null && p.contentEncoding().toLowerCase().contains("gzip");
        boolean json = p.contentType() != null && p.contentType().contains("json");
        if (!gzip && json) {
            out.put("body", new String(p.body(), java.nio.charset.StandardCharsets.UTF_8));
        } else {
            out.put("bodyBase64", Base64.getEncoder().encodeToString(p.body()));
        }
        return out;
    }
}
