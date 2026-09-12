package telemetry

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

const (
	spanNameExecution          = "gateway.execution"
	executionRoute             = "/v1/executions"
	attrHTTPRequestMethod      = "http.request.method"
	attrHTTPRoute              = "http.route"
	attrHTTPResponseStatusCode = "http.response.status_code"
	serviceNameValue           = "proofmesh-gateway"
	defaultShutdownTimeout     = 5 * time.Second
	exporterTimeout            = 10 * time.Second
	bspMaxQueueSize            = 2048
	bspMaxExportBatchSize      = 512
	bspBatchTimeout            = 5 * time.Second
	bspExportTimeout           = 30 * time.Second
)

// Sanitized sentinel errors returned across the telemetry package boundary.
// Network endpoints, connection details, and internal errors are never exposed.
var (
	ErrInitFailed     = errors.New("telemetry: initialization failed")
	ErrShutdownFailed = errors.New("telemetry: shutdown failed")
	ErrExportFailed   = errors.New("telemetry: export failed")
)

// Config carries opt-in telemetry configuration.
// When Endpoint is empty or whitespace-only, telemetry is disabled with zero network or export activity.
type Config struct {
	Endpoint string
}

// Enabled reports whether telemetry export is configured.
func (c Config) Enabled() bool {
	return strings.TrimSpace(c.Endpoint) != ""
}

// Telemetry manages lifecycle, context propagation, and span instrumentation.
// When disabled, all operations are safe no-ops and execution handlers are delegated directly.
type Telemetry struct {
	enabled      bool
	tp           *sdktrace.TracerProvider
	tracer       trace.Tracer
	propagator   propagation.TextMapPropagator
	shutdownMu   sync.Mutex
	shutdownDone bool
}

type options struct {
	exporter  sdktrace.SpanExporter
	processor sdktrace.SpanProcessor
}

type sanitizingExporter struct {
	inner sdktrace.SpanExporter
}

func (s *sanitizingExporter) ExportSpans(ctx context.Context, spans []sdktrace.ReadOnlySpan) error {
	if err := s.inner.ExportSpans(ctx, spans); err != nil {
		return ErrExportFailed
	}
	return nil
}

func (s *sanitizingExporter) Shutdown(ctx context.Context) error {
	if err := s.inner.Shutdown(ctx); err != nil {
		return ErrShutdownFailed
	}
	return nil
}

func deriveTracesURL(baseEndpoint string) (string, error) {
	if strings.TrimSpace(baseEndpoint) == "" {
		return "", errors.New("empty endpoint")
	}
	if baseEndpoint != strings.TrimSpace(baseEndpoint) {
		return "", errors.New("surrounding whitespace")
	}
	if strings.ContainsAny(baseEndpoint, " \t\r\n") {
		return "", errors.New("whitespace in endpoint")
	}
	u, err := url.Parse(baseEndpoint)
	if err != nil {
		return "", errors.New("parse error")
	}
	if u.Opaque != "" {
		return "", errors.New("opaque url")
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" {
		return "", errors.New("unsupported scheme")
	}
	if u.User != nil {
		return "", errors.New("userinfo not allowed")
	}
	if u.RawQuery != "" || u.ForceQuery {
		return "", errors.New("query not allowed")
	}
	if u.Fragment != "" {
		return "", errors.New("fragment not allowed")
	}
	host := u.Hostname()
	if host == "" {
		return "", errors.New("missing hostname")
	}
	portStr := u.Port()
	if portStr != "" {
		port, err := strconv.Atoi(portStr)
		if err != nil || port < 1 || port > 65535 {
			return "", errors.New("invalid port")
		}
	}
	if strings.HasPrefix(u.Host, "[") {
		idx := strings.Index(u.Host, "]")
		if idx == -1 {
			return "", errors.New("unclosed IPv6 bracket")
		}
		ipStr := u.Host[1:idx]
		ip := net.ParseIP(ipStr)
		if ip == nil || ip.To16() == nil {
			return "", errors.New("invalid IPv6 address")
		}
	} else if ip := net.ParseIP(host); ip != nil {
		// Valid IPv4
	} else {
		if strings.ContainsAny(host, ":/\\?#@") {
			return "", errors.New("invalid host characters")
		}
	}

	derived := *u
	derived.Scheme = scheme
	basePath := u.Path
	if basePath == "" || basePath == "/" {
		derived.Path = "/v1/traces"
	} else {
		derived.Path = path.Join(basePath, "v1/traces")
		if !strings.HasPrefix(derived.Path, "/") {
			derived.Path = "/" + derived.Path
		}
	}
	return derived.String(), nil
}

// New initializes OpenTelemetry tracing according to cfg.
// When Endpoint is empty or blank, telemetry is disabled and no exporter or background activity is started.
// When configured, it creates an OTLP/HTTP exporter with explicit service.name and W3C TraceContext propagation.
// Global OpenTelemetry provider and propagator state are never mutated.
func New(ctx context.Context, cfg Config) (*Telemetry, error) {
	return newTelemetry(ctx, cfg, options{})
}

func newTelemetry(ctx context.Context, cfg Config, opts options) (*Telemetry, error) {
	if !cfg.Enabled() {
		return &Telemetry{enabled: false}, nil
	}

	var exporter sdktrace.SpanExporter
	if opts.exporter != nil {
		exporter = opts.exporter
	} else {
		tracesURL, err := deriveTracesURL(cfg.Endpoint)
		if err != nil {
			return nil, ErrInitFailed
		}

		httpOpts := []otlptracehttp.Option{
			otlptracehttp.WithEndpointURL(tracesURL),
			otlptracehttp.WithHeaders(map[string]string{}),
			otlptracehttp.WithEncoding(otlptracehttp.EncodingProtobuf),
			otlptracehttp.WithCompression(otlptracehttp.NoCompression),
			otlptracehttp.WithTimeout(exporterTimeout),
			otlptracehttp.WithProxy(func(*http.Request) (*url.URL, error) { return nil, nil }),
		}
		if strings.HasPrefix(tracesURL, "https://") {
			httpOpts = append(httpOpts, otlptracehttp.WithTLSClientConfig(&tls.Config{
				MinVersion: tls.VersionTLS12,
			}))
		} else {
			httpOpts = append(httpOpts, otlptracehttp.WithInsecure())
		}

		exp, err := otlptracehttp.New(ctx, httpOpts...)
		if err != nil {
			return nil, ErrInitFailed
		}
		exporter = exp
	}

	sanitizedExp := &sanitizingExporter{inner: exporter}

	res := resource.NewWithAttributes(
		semconv.SchemaURL,
		attribute.String(string(semconv.ServiceNameKey), serviceNameValue),
	)

	var sp sdktrace.SpanProcessor
	if opts.processor != nil {
		sp = opts.processor
	} else {
		sp = sdktrace.NewBatchSpanProcessor(
			sanitizedExp,
			sdktrace.WithMaxQueueSize(bspMaxQueueSize),
			sdktrace.WithMaxExportBatchSize(bspMaxExportBatchSize),
			sdktrace.WithBatchTimeout(bspBatchTimeout),
			sdktrace.WithExportTimeout(bspExportTimeout),
		)
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithSpanProcessor(sp),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.AlwaysSample())),
	)

	return &Telemetry{
		enabled:    true,
		tp:         tp,
		tracer:     tp.Tracer(serviceNameValue),
		propagator: propagation.TraceContext{},
	}, nil
}

// Enabled reports whether this Telemetry instance is active.
func (t *Telemetry) Enabled() bool {
	return t != nil && t.enabled
}

// WrapExecutionHandler wraps the execution HTTP handler to extract W3C TraceContext,
// record a single SERVER span with bounded low-cardinality metadata, and record final status.
// If telemetry is disabled, or if the request is not a POST, the next handler is returned/called directly without creating a span.
func (t *Telemetry) WrapExecutionHandler(next http.Handler) http.Handler {
	if t == nil || !t.enabled {
		return next
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			next.ServeHTTP(w, r)
			return
		}

		ctx := t.propagator.Extract(r.Context(), propagation.HeaderCarrier(r.Header))
		ctx, span := t.tracer.Start(ctx, spanNameExecution,
			trace.WithSpanKind(trace.SpanKindServer),
			trace.WithAttributes(
				attribute.String(attrHTTPRequestMethod, r.Method),
				attribute.String(attrHTTPRoute, executionRoute),
			),
		)
		defer span.End()

		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r.WithContext(ctx))

		statusCode := sw.status
		if statusCode == 0 {
			statusCode = http.StatusOK
		}

		span.SetAttributes(attribute.Int(attrHTTPResponseStatusCode, statusCode))
		if statusCode >= 500 {
			span.SetStatus(codes.Error, "error")
		}
	})
}

// Shutdown flushes and stops the tracer provider within a bounded context.
// Repeated calls are idempotent and safe. When disabled, it returns nil immediately.
// Raw network and exporter errors are strictly mapped to ErrShutdownFailed.
func (t *Telemetry) Shutdown(ctx context.Context) error {
	if t == nil || !t.enabled || t.tp == nil {
		return nil
	}

	t.shutdownMu.Lock()
	defer t.shutdownMu.Unlock()
	if t.shutdownDone {
		return nil
	}
	t.shutdownDone = true

	if ctx == nil {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(context.Background(), defaultShutdownTimeout)
		defer cancel()
	} else if _, ok := ctx.Deadline(); !ok {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, defaultShutdownTimeout)
		defer cancel()
	}

	if err := t.tp.Shutdown(ctx); err != nil {
		return ErrShutdownFailed
	}
	return nil
}

type statusWriter struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
}

func (w *statusWriter) WriteHeader(code int) {
	if !w.wroteHeader {
		w.status = code
		w.wroteHeader = true
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(p []byte) (int, error) {
	if !w.wroteHeader {
		w.status = http.StatusOK
		w.wroteHeader = true
	}
	return w.ResponseWriter.Write(p)
}
