package io.github.cemesg.otlp.receiver.web;

import io.github.cemesg.otlp.receiver.consumer.BackpressureException;
import io.github.cemesg.otlp.receiver.consumer.MetricConsumer;
import io.github.cemesg.otlp.receiver.model.MetricPoint;
import io.github.cemesg.otlp.receiver.normalize.MetricNormalizer;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * OTLP/HTTP metrics ingest. Mounted at {@code ${otlp.receiver.path:/v1/metrics}}.
 * <p>
 * Per-request work (decode → parse → normalize+filter in one pass → consume) is
 * deliberately single-threaded; throughput comes from handling many requests
 * concurrently (virtual threads). The flow is <b>write-then-ack</b>: we only
 * return the OTLP success ack after {@link MetricConsumer#accept} returns, so a
 * failed/over-capacity write surfaces as a non-2xx and the Collector retries:
 * <ul>
 *   <li>{@link BackpressureException} → 503 + {@code Retry-After} (queue full).</li>
 *   <li>any other failure → 500 (Collector treats as retryable transient).</li>
 * </ul>
 */
@RestController
public class OtlpMetricsController {

    private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");

    private final MetricNormalizer normalizer;
    private final MetricConsumer consumer;

    public OtlpMetricsController(MetricNormalizer normalizer, MetricConsumer consumer) {
        this.normalizer = normalizer;
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

        boolean json = contentType != null && contentType.contains("json");
        boolean gzip = contentEncoding != null && contentEncoding.toLowerCase().contains("gzip");

        // The body is already buffered as a byte[]; we wrap it so protobuf and JSON
        // share one decode path with optional gzip. OTLP metric batches are bounded,
        // so full buffering is fine; take an InputStream arg instead for true streaming.
        ExportMetricsServiceRequest request;
        try (InputStream in = gzip ? new GZIPInputStream(new ByteArrayInputStream(body))
                                   : new ByteArrayInputStream(body)) {
            if (json) {
                ExportMetricsServiceRequest.Builder b = ExportMetricsServiceRequest.newBuilder();
                JsonFormat.parser().ignoringUnknownFields()
                        .merge(new String(in.readAllBytes(), StandardCharsets.UTF_8), b);
                request = b.build();
            } else {
                request = ExportMetricsServiceRequest.parseFrom(in);
            }
        }

        List<MetricPoint> points = normalizer.normalize(request);
        try {
            consumer.accept(points);   // write-then-ack
        } catch (BackpressureException bp) {
            // Shed load: tell the Collector to back off and retry rather than drop.
            return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "1").build();
        }
        // Any other failure propagates -> 500, which the Collector treats as retryable.

        // OTLP requires an ExportMetricsServiceResponse body; empty == full success.
        ExportMetricsServiceResponse ack = ExportMetricsServiceResponse.newBuilder().build();
        if (json) {
            byte[] out = JsonFormat.printer().print(ack).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);
        }
        return ResponseEntity.ok().contentType(PROTOBUF).body(ack.toByteArray());
    }
}
