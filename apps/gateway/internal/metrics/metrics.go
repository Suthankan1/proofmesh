// Package metrics exposes bounded execution HTTP measurements through a private
// Prometheus registry. It does not observe authorization claims or payloads.
package metrics

import (
	"net/http"

	"github.com/felixge/httpsnoop"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metrics is owned by one gateway process; it never registers global collectors.
type Metrics struct {
	requests *prometheus.CounterVec
	duration prometheus.Histogram
	inFlight prometheus.Gauge
	handler  http.Handler
}

// New creates a private registry with no environment/process collectors.
func New() *Metrics {
	registry := prometheus.NewRegistry()
	m := &Metrics{
		requests: prometheus.NewCounterVec(prometheus.CounterOpts{Name: "proofmesh_gateway_execution_requests_total", Help: "Completed execution HTTP requests by bounded status class, including failures."}, []string{"status_class"}),
		duration: prometheus.NewHistogram(prometheus.HistogramOpts{Name: "proofmesh_gateway_execution_duration_seconds", Help: "Execution HTTP handler latency in seconds, including authorization and tool dispatch.", Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30}}),
		inFlight: prometheus.NewGauge(prometheus.GaugeOpts{Name: "proofmesh_gateway_execution_in_flight", Help: "Execution HTTP requests currently being handled."}),
	}
	registry.MustRegister(m.requests, m.duration, m.inFlight)
	m.handler = promhttp.HandlerFor(registry, promhttp.HandlerOpts{MaxRequestsInFlight: 2, EnableOpenMetrics: true})
	return m
}

// WrapExecutionHandler records only the exact POST execution route. Response
// writer capabilities are preserved by the existing httpsnoop dependency.
func (m *Metrics) WrapExecutionHandler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/v1/executions" {
			next.ServeHTTP(w, r)
			return
		}
		m.inFlight.Inc()
		captured := httpsnoop.Metrics{Code: http.StatusOK}
		completed := false
		defer func() {
			m.inFlight.Dec()
			code := captured.Code
			if !completed {
				code = http.StatusInternalServerError
			}
			m.requests.WithLabelValues(statusClass(code)).Inc()
			m.duration.Observe(captured.Duration.Seconds())
		}()
		captured.CaptureMetrics(w, func(wrapped http.ResponseWriter) { next.ServeHTTP(wrapped, r) })
		completed = true
	})
}

func statusClass(code int) string {
	switch {
	case code >= 200 && code < 300:
		return "2xx"
	case code >= 300 && code < 400:
		return "3xx"
	case code >= 400 && code < 500:
		return "4xx"
	case code >= 500 && code < 600:
		return "5xx"
	default:
		return "other"
	}
}

// Routes exposes exact GET /metrics and delegates all application routes.
// Operators must restrict scrape access at the network/ingress boundary.
func (m *Metrics) Routes(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/metrics" {
			next.ServeHTTP(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		if r.Method != http.MethodGet {
			w.Header().Set("Allow", http.MethodGet)
			http.Error(w, "method_not_allowed", http.StatusMethodNotAllowed)
			return
		}
		m.handler.ServeHTTP(w, r)
	})
}
