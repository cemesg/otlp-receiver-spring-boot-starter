package io.github.cemesg.otlp.receiver.raw;

import java.time.Instant;

/**
 * One OTLP/HTTP request captured verbatim — the exact bytes the collector sent,
 * plus the headers needed to interpret them later. Nothing is decoded.
 */
public record RawPayload(Instant receivedAt, String contentType, String contentEncoding, byte[] body) {

    public int sizeBytes() {
        return body.length;
    }
}
