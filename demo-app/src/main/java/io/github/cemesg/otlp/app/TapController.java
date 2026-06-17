package io.github.cemesg.otlp.app;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Demo-only. Serves "taps" for the dashboard: the most recent raw payload each
 * Collector wrote via its file exporter. Each tap file holds one OTLP-JSON
 * payload per line, so the latest non-blank line is the freshest sample at that
 * pipeline boundary. Independent of the starter's OTLP ingest path.
 */
@RestController
public class TapController {

    private static final Path TAP_DIR =
            Path.of(System.getenv().getOrDefault("TAP_DIR", "/taps"));

    /** Latest raw payload at a pipeline boundary: {@code beyla} or {@code gateway}. */
    @GetMapping(value = "/api/tap/{stage}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> tap(@PathVariable String stage) {
        String file = switch (stage) {
            case "beyla" -> "beyla.json";
            case "gateway" -> "gateway.json";
            default -> null;
        };
        if (file == null) {
            return ResponseEntity.badRequest().body("{\"error\":\"unknown stage\"}");
        }
        Path p = TAP_DIR.resolve(file);
        if (!Files.exists(p)) {
            return ResponseEntity.ok("{\"status\":\"no data yet\"}");
        }
        try {
            // 1MB-rotated files: cheap to read whole and take the last line.
            byte[] bytes = Files.readAllBytes(p);
            String content = new String(bytes, StandardCharsets.UTF_8);
            String last = "";
            for (String line : content.split("\n")) {
                if (!line.isBlank()) last = line;
            }
            return ResponseEntity.ok(last.isBlank() ? "{\"status\":\"no data yet\"}" : last);
        } catch (Exception e) {
            // File may be mid-rotation; tell the client to retry on its next poll.
            return ResponseEntity.ok("{\"status\":\"reading, retry\"}");
        }
    }
}
