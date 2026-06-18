package io.github.cemesg.otlp.receiver.raw;

import com.google.protobuf.util.JsonFormat;
import io.github.cemesg.otlp.receiver.consumer.BackpressureException;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * {@code raw}-mode OTLP/HTTP ingest. Same path as the normalize-mode endpoint
 * ({@code ${otlp.receiver.path:/v1/metrics}}) — only one is active at a time —
 * but this one does <b>no decoding</b>: it hands the request body verbatim to a
 * {@link RawMetricConsumer} and returns the OTLP ack. The protobuf parse and the
 * flatten/normalize are skipped entirely, so ingest is as cheap as a byte copy;
 * you process the stored bytes later. Backpressure still maps to 503 so the
 * Collector retries.
 */
@RestController
public class RawOtlpController {

    private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");

    private final RawMetricConsumer consumer;

    public RawOtlpController(RawMetricConsumer consumer) {
        this.consumer = consumer;
    }

    @PostMapping(
            value = "${otlp.receiver.path:/v1/metrics}",
            consumes = {"application/x-protobuf", "application/json"},
            produces = {"application/x-protobuf", "application/json"})
    public ResponseEntity<byte[]> ingest(
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String contentEncoding)
            throws IOException {

        try {
            consumer.accept(new RawPayload(Instant.now(), contentType, contentEncoding, body));
        } catch (BackpressureException bp) {
            return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "1").build();
        }

        // Still a valid OTLP backend: return the standard (empty == success) ack.
        ExportMetricsServiceResponse ack = ExportMetricsServiceResponse.newBuilder().build();
        boolean json = contentType != null && contentType.contains("json");
        if (json) {
            byte[] out = JsonFormat.printer().print(ack).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);
        }
        return ResponseEntity.ok().contentType(PROTOBUF).body(ack.toByteArray());
    }
}
