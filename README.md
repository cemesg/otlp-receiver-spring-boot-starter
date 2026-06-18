# OTLP Metrics Receiver → clean per-point JSON

A Spring Boot **auto-configuration ("starter")** that receives OTLP/HTTP metrics,
normalizes the nested/polymorphic OTLP structure into a flat per-data-point model,
filters it, and hands it to a pluggable consumer. Drop it into any Spring Boot app.

## Project layout

A Maven multi-module reactor:

```
otlp-receiver-parent/                         (pom — reactor + dependency mgmt)
├── otlp-receiver-spring-boot-starter/        the reusable library
│   └── io.github.cemesg.otlp.receiver/
│       ├── model/      MetricPoint
│       ├── normalize/  MetricNormalizer
│       ├── filter/     MetricFilter
│       ├── consumer/   MetricConsumer, InMemoryMetricConsumer, AsyncMetricConsumer, BackpressureException
│       ├── web/        OtlpMetricsController, MetricsViewController
│       └── config/     OtlpReceiverProperties, OtlpReceiverAutoConfiguration
├── demo-app/                                 runnable demo: main class + dashboard taps
│   └── io.github.cemesg.otlp.app/  + resources (application.yml, static/index.html)
└── bench/                                     JMH A/B harness (separate; depends on the starter jar)
```

The starter has unit tests under `src/test/java` (normalizer lossless/ordering,
filter globs + bitmask, consumers, and an auto-configuration context test).

## Build & test

```bash
mvn test          # build the reactor + run all unit tests
mvn -q install    # install the starter jar locally (needed before building bench)
```

## Use as a reusable starter

Add the starter as a dependency to any Spring Boot 3.2+ app on Java 21:

```xml
<dependency>
  <groupId>io.github.cemesg.otlp</groupId>
  <artifactId>otlp-receiver-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

It self-wires via `META-INF/spring/...AutoConfiguration.imports` — no `@Import`
needed. Every bean is `@ConditionalOnMissingBean`, so override any piece:

```java
// Replace the default in-memory sink with your own durable write target.
@Bean
MetricConsumer metricConsumer(MyKafkaWriter writer) {
    return writer;                       // or: new AsyncMetricConsumer(writer, 10_000, 4);
}
```

With no consumer bean defined you get the built-in `InMemoryMetricConsumer`
(ring buffer + SSE) and the `/api/metrics/*` read API for free.

### Configuration (`otlp.receiver.*`)

All filtering is glob-based (`*`) and applied **during** the normalize pass, so
trimming what you don't need is cheap:

| Property | Effect |
|---|---|
| `path` | Ingest path (default `/v1/metrics`). |
| `mode` | `normalize` (default) or `raw` — see [Raw mode](#raw-mode-store-now-process-later). |
| `types` | Keep only these metric types (empty = all). |
| `drop-empty-values` | Drop attribute/source entries with `""`/null values. |
| `metrics.include` / `metrics.exclude` | Filter by metric name. |
| `source.include` / `source.exclude` | Filter resource/identity keys (e.g. `k8s.*`). |
| `attributes.include` / `attributes.exclude` | Filter data-point dimension keys. |

## Raw mode (store now, process later)

By default the receiver decodes + normalizes on ingest. If instead you want to
**store the OTLP body verbatim and process it elsewhere/later** (e.g. tee to
Kafka or object storage), set:

```yaml
otlp:
  receiver:
    mode: raw
```

Then the same ingest path skips all decoding — it hands the raw request bytes to
a `RawMetricConsumer` and returns the OTLP ack — and exposes them on
`GET /api/raw/recent`. The default `InMemoryRawMetricConsumer` is a dev sink;
for production define your own bean to write the bytes somewhere durable:

```java
@Bean
RawMetricConsumer rawMetricConsumer(MyKafkaWriter writer) {
    return payload -> writer.send(payload.body());   // store the exact OTLP bytes
}
```

It's a self-contained `…receiver.raw` package (consumer SPI, in-memory impl,
ingest + read controllers) — copy it out if you only want this mode.

**When raw wins:** you're a durable buffer feeding a real downstream pipeline.
**When it doesn't:** if you want query-ready data directly, normalize-on-ingest
(the cost is ~0.18 µs/point — negligible vs I/O). Storing raw doesn't avoid the
work, it defers it; store **protobuf** (not JSON) bytes to keep storage small.

## Performance & scaling

- **Per request is single-threaded on purpose.** One OTLP request is already a
  batch; the work (decode → parse → normalize+filter in one pass) is a tight CPU
  loop. Concurrency comes from handling **many requests at once**, not forking one.
- **Virtual threads** (`spring.threads.virtual.enabled=true`, Java 21) give cheap
  high concurrency for the I/O-bound write path — no pool tuning.
- **Write-then-ack with backpressure.** Ingest acks only after the consumer
  accepts. A consumer can throw `BackpressureException` → the receiver returns
  **503 + Retry-After**, and the Collector queues + retries. The Collector is your
  buffer/retry layer; the receiver sheds load instead of growing unbounded.
- **Horizontal scale:** the reshape+forward path is stateless → run N replicas
  behind a Service. ⚠️ For *cumulative* correctness, route a given series
  consistently (Collector `loadbalancing` exporter hashes by resource) or push
  aggregation downstream to the TSDB. The `InMemoryMetricConsumer` is per-instance
  (not shared across replicas) — a dev sink, not the scale-out path.

## Endpoints

| Method | Path                  | Purpose                                                        |
|--------|-----------------------|----------------------------------------------------------------|
| POST   | `/v1/metrics`         | OTLP ingest (protobuf or JSON, gzip-aware). Returns OTLP ack.   |
| GET    | `/api/metrics/recent` | Snapshot of the most recent normalized points (JSON array).     |
| GET    | `/api/metrics/stream` | Live stream of normalized points (`text/event-stream`).         |

The `/v1/metrics` response is the standard `ExportMetricsServiceResponse`, so a
Collector/Beyla exporter treats this as a valid OTLP backend. The *user-facing*
data comes out of the `/api/metrics/*` endpoints in your own schema.

## Run

```bash
mvn -q install                      # build/install the starter once
mvn -pl demo-app spring-boot:run    # run the demo app (port 4318)
```

Or run the whole Dockerized pipeline (Beyla → collectors → receiver): see [stack/](stack/).

Point a Collector at it (note `encoding: json` is optional — protobuf is the default):

```yaml
exporters:
  otlphttp:
    metrics_endpoint: http://localhost:4318/v1/metrics
    # encoding: json   # uncomment to send OTLP/JSON instead of protobuf
service:
  pipelines:
    metrics:
      exporters: [otlphttp]
```

Watch normalized metrics live:

```bash
curl -N http://localhost:4318/api/metrics/stream
```

## Output shape

One JSON object per data point. Identity is separated from the metric's own
dimensions:

- **`source`** — *who emitted this*: the OTLP resource attributes, ordered so the
  canonical service / Kubernetes / host / cloud identity keys come first
  (`service.name`, `service.namespace`, `k8s.namespace.name`, `k8s.pod.name`,
  `k8s.node.name`, `k8s.deployment.name`, …). Lossless: any other resource
  attribute is appended.
- **`attributes`** — the metric's dimensions (`http.route`, `http.response.status_code`, …).

```json
{
  "name": "http.server.request.duration",
  "unit": "s",
  "type": "histogram",
  "timestamp": "2026-06-17T10:30:00Z",
  "source": {
    "service.name": "checkout",
    "service.namespace": "storefront",
    "k8s.namespace.name": "storefront",
    "k8s.deployment.name": "checkout",
    "k8s.pod.name": "checkout-7d9f9c-abcde",
    "k8s.node.name": "worker-node-01",
    "telemetry.sdk.name": "beyla"
  },
  "attributes": {
    "http.request.method": "GET",
    "http.route": "/users/{id}",
    "http.response.status_code": 200
  },
  "value": {
    "count": 150,
    "sum": 9.45,
    "bounds": [0.005, 0.05, 0.25],
    "buckets": [12, 88, 140, 150],
    "temporality": "cumulative",
    "average": 0.063
  }
}
```

In Kubernetes/OpenShift the `k8s.*` keys are injected automatically by the
Collector's `k8sattributes` processor (or the downward API); in the local Docker
stack they're set on Beyla via `OTEL_RESOURCE_ATTRIBUTES` to simulate that. The
ordered identity key list lives in `MetricNormalizer.IDENTITY_KEYS`.

## Caveat: protobuf version alignment

`protobuf-java` and `protobuf-java-util` must be the same version and should match
the `protobuf-java` that `io.opentelemetry.proto` pulls in. After the first build:

```bash
mvn dependency:tree | grep protobuf
```

Set the `<protobuf.version>` property in `pom.xml` to the version shown.
