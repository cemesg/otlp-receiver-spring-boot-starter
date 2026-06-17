package io.github.cemesg.otlp.receiver.consumer;

/**
 * Thrown by a {@link MetricConsumer} when it cannot accept more data right now
 * (e.g. an async buffer is full). The ingest controller maps this to HTTP 503
 * with {@code Retry-After}, which is exactly what an OTLP exporter / Collector
 * expects: it will queue and retry rather than drop. This is how the receiver
 * sheds load instead of growing an unbounded internal queue.
 */
public class BackpressureException extends RuntimeException {
    public BackpressureException(String message) {
        super(message);
    }
}
