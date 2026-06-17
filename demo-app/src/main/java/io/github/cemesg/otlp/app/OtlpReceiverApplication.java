package io.github.cemesg.otlp.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo app. It contributes nothing but a main class and the dashboard's tap
 * endpoints — the OTLP ingest, normalization, filtering, and SSE/snapshot API
 * all come from the {@code otlp-receiver-spring-boot-starter} via auto-configuration.
 */
@SpringBootApplication
public class OtlpReceiverApplication {
    public static void main(String[] args) {
        SpringApplication.run(OtlpReceiverApplication.class, args);
    }
}
