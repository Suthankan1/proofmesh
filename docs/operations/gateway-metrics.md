# Gateway Prometheus metrics

The gateway exposes exact `GET /metrics` on its existing HTTP listener (8080).
Scrapes use a process-local Prometheus registry; no global registry, Go runtime,
process, environment, or request-derived collectors are registered. The endpoint
returns `Cache-Control: no-store`; other methods return 405 with `Allow: GET`.
Only exact `POST /v1/executions` requests are measured. Health/readiness probes,
scrapes, unknown paths, and rejected non-POST methods do not affect execution
metrics. Tracing remains independently opt-in.

| Metric | Type | Meaning |
| --- | --- | --- |
| `proofmesh_gateway_execution_requests_total{status_class}` | Counter | Completed execution HTTP handlers, grouped into `2xx`, `3xx`, `4xx`, `5xx`, or `other` |
| `proofmesh_gateway_execution_duration_seconds` | Histogram | Handler latency across verification, exact binding, durable claim and tool dispatch; fixed buckets from 5 ms to 30 s plus infinity |
| `proofmesh_gateway_execution_in_flight` | Gauge | Execution handlers currently active |

These are HTTP measurements, **not** an execution audit or proof of downstream
success. A 2xx response describes the gateway HTTP result. A rejected or replayed
request also increments the HTTP request counter. Handler panics propagate to
the existing HTTP server recovery behavior, record a 5xx observation and release
the in-flight gauge; the metric is not a promise that a 500 response reached the
client. Counters reset when a process restarts.

No organization/agent/action/grant IDs, tokens, payloads, headers, query strings,
tool/operation names, arbitrary paths, exception text, or network endpoints enter
metric labels. Label cardinality is fixed. There are no exemplars or trace IDs.
Prometheus controls scrape identity labels outside the gateway.

## Scraping and access

Restrict `/metrics` to the monitoring network at ingress and NetworkPolicy
boundaries. It has no application authentication; do not expose it through a
public execution ingress. The Kubernetes release helper creates an internal
ClusterIP Service, but that alone does not restrict access within a cluster.
Limit ingress to exact execution routes and provision monitoring access
separately. Scrapes are bounded to two simultaneous Prometheus handler calls.

Scrape **each replica directly**, using your existing Prometheus pod discovery or
PodMonitor convention. Scraping a load-balanced Service as a single target mixes
independent process counters and produces misleading rates. Example static
configuration for two independently addressable replicas (replace targets):

```yaml
scrape_configs:
  - job_name: proofmesh-gateway
    metrics_path: /metrics
    scrape_interval: 15s
    scrape_timeout: 5s
    static_configs:
      - targets: ['gateway-replica-1:8080', 'gateway-replica-2:8080']
```

Useful queries:

```promql
sum(rate(proofmesh_gateway_execution_requests_total[5m]))
sum(rate(proofmesh_gateway_execution_requests_total{status_class="5xx"}[5m]))
histogram_quantile(0.95, sum by (le) (rate(proofmesh_gateway_execution_duration_seconds_bucket[5m])))
sum(proofmesh_gateway_execution_in_flight)
```

Set alert thresholds from measured load, availability targets, and deliberate
failure exercises. This slice does not deploy a Prometheus server, supply paging
thresholds, or add control-plane business metrics. Validate scrape discovery and
monitoring access on your target cluster before treating it as operationally
ready.

Local verification: `go test -count=1 -race ./internal/metrics ./cmd/gateway` from
`apps/gateway`. The official Prometheus client is pinned in `go.mod`; httpsnoop
preserves existing response-writer capabilities without custom wrappers.
