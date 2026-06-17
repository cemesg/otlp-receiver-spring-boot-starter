# Beyla → OTel agent → OTel gateway → Java receiver (Dockerized prod-like stack)

A full metrics pipeline on one Docker network. Beyla auto-instruments a sample
HTTP service with eBPF, ships OTLP to a node-tier Collector (which also scrapes
system metrics), that forwards to a gateway-tier Collector (the central
"control plane"), which exports to the Spring Boot OTLP receiver in the parent
directory. The receiver normalizes OTLP into flat per-point JSON and serves it
over SSE.

```
loadgen ──HTTP──▶ sampleapp ──watched by eBPF──▶ beyla
                                                   │ OTLP/HTTP
                                                   ▼
                            otelcol-agent (OTLP in + hostmetrics)
                                                   │ OTLP/gRPC
                                                   ▼
                            otelcol-gateway (tags pipeline.gateway, batches)
                                                   │ OTLP/HTTP -> /v1/metrics
                                                   ▼
                            java-receiver (normalize -> /api/metrics/*)
```

## Run

```bash
cd stack
docker compose up -d --build      # build the Java image + start all 6 services
docker compose ps
```

## Dashboard

Open **http://localhost:4318/** for a live UI with three panels showing sampled
payloads at each pipeline boundary:

1. **Beyla eBPF output** — raw OTLP the agent receives from Beyla (isolated in
   its own agent pipeline, so hostmetrics don't appear here).
2. **Gateway → backend output** — raw OTLP the gateway exports to the receiver.
3. **Receiver normalized output** — the clean per-point JSON, live via SSE.

Panels ① and ② are fed by a `file` exporter on each Collector (rotating, 1 MB)
writing to a shared `taps` volume; the receiver reads them via `/api/tap/{beyla,gateway}`.

## Watch the data (CLI)

```bash
# Snapshot of the most recent normalized points
curl -s http://localhost:4318/api/metrics/recent | jq '.[0]'

# Live stream
curl -N http://localhost:4318/api/metrics/stream

# What's flowing, by metric name
curl -s http://localhost:4318/api/metrics/recent \
  | jq -r '.[].name' | sort | uniq -c | sort -rn
```

You'll see Beyla's eBPF HTTP metrics (`http.server.request.duration`,
`http.server.request.body.size`, ...) tagged `service.name=sampleapp` /
`telemetry.sdk.name=beyla`, plus `system.*` hostmetrics — every point carries
`pipeline.gateway=otelcol-gateway`, proving it traversed the gateway tier.

Browse http://localhost:8080 to generate extra traffic for Beyla to observe.

## Components

| Service          | Image                                       | Role                                             |
|------------------|---------------------------------------------|--------------------------------------------------|
| `sampleapp`      | `python:3.12-alpine`                        | Plain HTTP server — the instrumented workload    |
| `loadgen`        | `curlimages/curl`                           | Generates request traffic (incl. 404s)           |
| `beyla`          | `grafana/beyla`                             | eBPF auto-instrumentation → OTLP                 |
| `otelcol-agent`  | `otel/opentelemetry-collector-contrib`      | Node tier: OTLP in + hostmetrics → gateway       |
| `otelcol-gateway`| `otel/opentelemetry-collector-contrib`      | Central tier: policy/tagging → Java backend      |
| `java-receiver`  | built from `../` via `Dockerfile.receiver`  | OTLP backend: normalize → SSE/snapshot           |

## Notes

- **Beyla & eBPF:** runs `privileged` and shares the sample app's PID namespace
  (`pid: service:sampleapp`) so it can find the process, while keeping its own
  network namespace to reach the agent. On the Docker Desktop Linux VM kernel,
  pinned-map features (log enricher) are disabled — harmless for metrics.
- **hostmetrics safety net:** the agent scrapes `system.*` so the pipeline shows
  data end-to-end even where eBPF is constrained.
- **protobuf alignment:** `opentelemetry-proto:1.9.0-alpha` is generated with
  protobuf gencode `4.33.1`; the receiver runtime must be ≥ that, so
  `../pom.xml` sets `<protobuf.version>4.33.1</protobuf.version>`. A lower value
  causes HTTP 500s on ingest (`ProtobufRuntimeVersionException`).
- **Pinning:** Collector/Beyla images use `:latest` for convenience — pin to
  specific tags for anything long-lived.

## Tear down

```bash
docker compose down
```
