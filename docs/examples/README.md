# Example payloads

Real captures from the [Dockerized pipeline](../../stack/) (Beyla → OTel agent →
OTel gateway → receiver), trimmed to a single metric / data point.

| File | What it is |
|------|------------|
| [`collector-otlp-output.json`](collector-otlp-output.json) | What an OpenTelemetry Collector **sends** to the receiver — raw OTLP/JSON: nested `resourceMetrics → scopeMetrics → metrics`, polymorphic `histogram` data point, attributes as `{key, value:{stringValue:…}}` lists. |
| [`normalized-output.json`](normalized-output.json) | What the receiver **emits** on `/api/metrics/{recent,stream}` — one flat record per data point: emitting-service identity in `source` (k8s/service keys first), metric dimensions in `attributes`, real `long`/`double` numbers in `value`. |

The receiver turns the left into the right: one OTLP request fans out into one
record per data point, the `oneof`/`AnyValue` unions become plain JSON, and
resource identity is separated from metric dimensions.
