package telemetry

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"go.opentelemetry.io/otel/codes"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

// Helper to construct a test Telemetry instance backed by an in-memory exporter.
func setupTestTelemetry(t *testing.T) (*Telemetry, *tracetest.InMemoryExporter) {
	t.Helper()
	exp := tracetest.NewInMemoryExporter()
	tel, err := newTelemetry(context.Background(), Config{Endpoint: "http://otel.test:4318"}, options{
		exporter:  exp,
		processor: sdktrace.NewSimpleSpanProcessor(exp),
	})
	if err != nil {
		t.Fatalf("failed to create test telemetry: %v", err)
	}
	t.Cleanup(func() {
		_ = tel.Shutdown(context.Background())
	})
	return tel, exp
}

// A. Disabled mode:
// - no exporter traffic
// - wrapper delegates normally
// - shutdown is safe no-op
// - whitespace-only config treated as disabled
func TestTelemetry_Disabled(t *testing.T) {
	for _, cfg := range []Config{
		{},
		{Endpoint: ""},
		{Endpoint: "   "},
		{Endpoint: "\t\n "},
	} {
		tel, err := New(context.Background(), cfg)
		if err != nil {
			t.Fatalf("expected nil error on disabled config %+v, got %v", cfg, err)
		}
		if tel.Enabled() {
			t.Fatalf("expected telemetry to be disabled for %+v", cfg)
		}

		called := false
		handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			called = true
			w.WriteHeader(http.StatusOK)
		}))

		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
		handler.ServeHTTP(rec, req)

		if !called {
			t.Fatal("expected delegate handler to be called")
		}
		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", rec.Code)
		}

		if err := tel.Shutdown(context.Background()); err != nil {
			t.Fatalf("expected nil error on disabled shutdown, got %v", err)
		}
	}

	// Nonblank with surrounding whitespace must be rejected
	for _, spaced := range []string{
		" http://collector:4318",
		"http://collector:4318 ",
		" http://collector:4318 ",
	} {
		_, err := New(context.Background(), Config{Endpoint: spaced})
		if err == nil {
			t.Fatalf("expected error for endpoint with surrounding whitespace %q, got nil", spaced)
		}
		if !errors.Is(err, ErrInitFailed) {
			t.Fatalf("expected ErrInitFailed for %q, got %v", spaced, err)
		}
	}
}

// B. Parent propagation:
// Valid incoming traceparent:
// - server span uses incoming trace ID
// - creates a new span ID
func TestTelemetry_ParentPropagation(t *testing.T) {
	tel, exp := setupTestTelemetry(t)

	incomingTraceID := "4bf92f3577b34da6a3ce929d0e0e4736"
	incomingParentSpanID := "00f067aa0ba902b7"
	traceparent := fmt.Sprintf("00-%s-%s-01", incomingTraceID, incomingParentSpanID)

	handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
	req.Header.Set("traceparent", traceparent)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	spans := exp.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected 1 span, got %d", len(spans))
	}

	span := spans[0]
	if span.SpanContext.TraceID().String() != incomingTraceID {
		t.Fatalf("expected trace ID %s, got %s", incomingTraceID, span.SpanContext.TraceID().String())
	}
	if span.SpanContext.SpanID().String() == incomingParentSpanID {
		t.Fatal("server span ID must differ from parent span ID")
	}
	if span.Parent.SpanID().String() != incomingParentSpanID {
		t.Fatalf("expected parent span ID %s, got %s", incomingParentSpanID, span.Parent.SpanID().String())
	}
}

// C. Fixed span name and span kind:
// Name must be exactly "gateway.execution", Kind must be SERVER
func TestTelemetry_FixedSpanNameAndKind(t *testing.T) {
	tel, exp := setupTestTelemetry(t)

	handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	spans := exp.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected 1 span, got %d", len(spans))
	}

	span := spans[0]
	if span.Name != "gateway.execution" {
		t.Fatalf("expected span name 'gateway.execution', got %q", span.Name)
	}
	if span.SpanKind != trace.SpanKindServer {
		t.Fatalf("expected SERVER span kind, got %v", span.SpanKind)
	}
}

// D. Bounded attributes:
// Assert only safe bounded metadata (method, route, status code)
func TestTelemetry_BoundedAttributes(t *testing.T) {
	tel, exp := setupTestTelemetry(t)

	handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	spans := exp.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected 1 span, got %d", len(spans))
	}

	span := spans[0]
	attrMap := make(map[string]any)
	for _, a := range span.Attributes {
		attrMap[string(a.Key)] = a.Value.AsInterface()
	}

	expectedKeys := map[string]bool{
		"http.request.method":       true,
		"http.route":                true,
		"http.response.status_code": true,
	}

	if len(attrMap) != len(expectedKeys) {
		t.Fatalf("expected exactly %d attributes, got %d: %+v", len(expectedKeys), len(attrMap), attrMap)
	}

	for k := range attrMap {
		if !expectedKeys[k] {
			t.Errorf("unexpected attribute key: %s", k)
		}
	}

	if attrMap["http.request.method"] != "POST" {
		t.Errorf("expected POST method, got %v", attrMap["http.request.method"])
	}
	if attrMap["http.route"] != "/v1/executions" {
		t.Errorf("expected /v1/executions route, got %v", attrMap["http.route"])
	}
	if attrMap["http.response.status_code"] != int64(200) {
		t.Errorf("expected 200 status code, got %v", attrMap["http.response.status_code"])
	}
}

// E. Sensitive-data absence:
// Canary token/payload/UUID/tool values must never appear in:
// - span name
// - attributes (keys or values)
// - events
// - status description
func TestTelemetry_SensitiveDataAbsence(t *testing.T) {
	tel, exp := setupTestTelemetry(t)

	canaries := []string{
		"eyJhbGciOiJFUzI1NiJ9.canary_jwt_secret_token",
		"canary-org-uuid-1111-2222",
		"canary-agent-uuid-3333-4444",
		"canary-action-uuid-5555-6666",
		"canary-decision-uuid-7777-8888",
		"canary-grant-jti-9999",
		"canary_tool_payments",
		"canary_op_charge",
		"canary_payload_hash_abcdef0123456789",
		"postgres://secret:pwd@canary-db:5432/proofmesh",
		"https://canary-jwks.internal/keys.json",
		"https://canary-target.internal/v1/charge",
		"internal sql connection reset by peer canary failure",
	}

	handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Handler responds with 500 error
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":"canary_internal_error"}`))
	}))

	req := httptest.NewRequest(http.MethodPost, executionRoute, strings.NewReader(`{"canary":"body"}`))
	req.Header.Set("Authorization", "Bearer "+canaries[0])
	req.Header.Set("X-Canary-Header", "canary-header-val")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	spans := exp.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected 1 span, got %d", len(spans))
	}

	span := spans[0]

	assertNoCanary := func(location, value string) {
		for _, canary := range canaries {
			if strings.Contains(strings.ToLower(value), strings.ToLower(canary)) {
				t.Fatalf("canary leak in %s: %q leaked into %q", location, canary, value)
			}
		}
	}

	assertNoCanary("span.Name", span.Name)
	assertNoCanary("span.Status.Description", span.Status.Description)

	for _, a := range span.Attributes {
		assertNoCanary("attribute.Key", string(a.Key))
		assertNoCanary("attribute.Value", fmt.Sprintf("%v", a.Value.AsInterface()))
	}

	for _, ev := range span.Events {
		assertNoCanary("event.Name", ev.Name)
		for _, a := range ev.Attributes {
			assertNoCanary("event.attr.Key", string(a.Key))
			assertNoCanary("event.attr.Value", fmt.Sprintf("%v", a.Value.AsInterface()))
		}
	}

	// Status description must be sanitized "error", never containing raw text
	if span.Status.Code == codes.Error && span.Status.Description != "error" {
		t.Fatalf("expected sanitized status description 'error', got %q", span.Status.Description)
	}
}

// F. Response statuses:
// Delegate representative results such as 200, 401, 409, 500 and confirm bounded status-code recording.
func TestTelemetry_ResponseStatuses(t *testing.T) {
	for _, tc := range []struct {
		statusCode     int
		expectError    bool
		writeBodyEarly bool
	}{
		{statusCode: http.StatusOK, expectError: false},
		{statusCode: http.StatusBadRequest, expectError: false},
		{statusCode: http.StatusUnauthorized, expectError: false},
		{statusCode: http.StatusForbidden, expectError: false},
		{statusCode: http.StatusConflict, expectError: false},
		{statusCode: http.StatusInternalServerError, expectError: true},
		{statusCode: http.StatusBadGateway, expectError: true},
		{statusCode: http.StatusServiceUnavailable, expectError: true},
		{statusCode: http.StatusGatewayTimeout, expectError: true},
		{statusCode: http.StatusOK, expectError: false, writeBodyEarly: true},
	} {
		t.Run(fmt.Sprintf("status_%d", tc.statusCode), func(t *testing.T) {
			tel, exp := setupTestTelemetry(t)

			handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if tc.writeBodyEarly {
					// Implicit 200 by writing body without explicit WriteHeader
					_, _ = w.Write([]byte("ok"))
					return
				}
				w.WriteHeader(tc.statusCode)
			}))

			req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)

			spans := exp.GetSpans()
			if len(spans) != 1 {
				t.Fatalf("expected 1 span, got %d", len(spans))
			}

			span := spans[0]
			var recordedStatus int64
			for _, a := range span.Attributes {
				if string(a.Key) == "http.response.status_code" {
					recordedStatus = a.Value.AsInt64()
				}
			}

			if recordedStatus != int64(tc.statusCode) {
				t.Fatalf("expected recorded status %d, got %d", tc.statusCode, recordedStatus)
			}

			if tc.expectError {
				if span.Status.Code != codes.Error {
					t.Fatalf("expected error status for %d, got %v", tc.statusCode, span.Status.Code)
				}
				if span.Status.Description != "error" {
					t.Fatalf("expected status description 'error', got %q", span.Status.Description)
				}
			} else {
				if span.Status.Code == codes.Error {
					t.Fatalf("expected non-error status for %d, got %v", tc.statusCode, span.Status.Code)
				}
			}
		})
	}
}

// G. Service resource:
// service.name = proofmesh-gateway
// No dynamic host/user/repository identifiers.
func TestTelemetry_Resource(t *testing.T) {
	tel, exp := setupTestTelemetry(t)

	handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	spans := exp.GetSpans()
	if len(spans) != 1 {
		t.Fatalf("expected 1 span, got %d", len(spans))
	}

	res := spans[0].Resource
	if res == nil {
		t.Fatal("expected span resource to be non-nil")
	}

	attrs := res.Attributes()
	serviceNameFound := false
	for _, a := range attrs {
		if string(a.Key) == "service.name" {
			serviceNameFound = true
			if a.Value.AsString() != "proofmesh-gateway" {
				t.Fatalf("expected service.name 'proofmesh-gateway', got %q", a.Value.AsString())
			}
		}
		// Ensure no host, process, user or git identifiers were injected
		k := strings.ToLower(string(a.Key))
		if strings.Contains(k, "host") || strings.Contains(k, "user") || strings.Contains(k, "process") || strings.Contains(k, "git") {
			t.Fatalf("unexpected dynamic resource attribute: %s=%v", a.Key, a.Value)
		}
	}

	if !serviceNameFound {
		t.Fatal("expected service.name attribute in resource")
	}
}

// H. Lifecycle & Shutdown:
// Bounded context, idempotent, flushed exporter.
func TestTelemetry_Shutdown(t *testing.T) {
	exp := tracetest.NewInMemoryExporter()
	tel, err := newTelemetry(context.Background(), Config{Endpoint: "http://otel.test:4318"}, options{exporter: exp})
	if err != nil {
		t.Fatalf("failed to create telemetry: %v", err)
	}

	// First shutdown succeeds
	if err := tel.Shutdown(context.Background()); err != nil {
		t.Fatalf("expected nil on first shutdown, got %v", err)
	}

	// Second shutdown is idempotent and returns nil
	if err := tel.Shutdown(context.Background()); err != nil {
		t.Fatalf("expected nil on second shutdown, got %v", err)
	}
}

// Timeout during shutdown maps to ErrShutdownFailed.
func TestTelemetry_ShutdownTimeout(t *testing.T) {
	exp := tracetest.NewInMemoryExporter()
	tel, err := newTelemetry(context.Background(), Config{Endpoint: "http://otel.test:4318"}, options{exporter: exp})
	if err != nil {
		t.Fatalf("failed to create telemetry: %v", err)
	}

	// Pass an already canceled context
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	err = tel.Shutdown(ctx)
	if err == nil {
		t.Fatal("expected error on canceled context shutdown")
	}
	if !errors.Is(err, ErrShutdownFailed) {
		t.Fatalf("expected ErrShutdownFailed, got %v", err)
	}
}

type recordingExporter struct {
	sync.Mutex
	spans []sdktrace.ReadOnlySpan
}

func (r *recordingExporter) ExportSpans(ctx context.Context, spans []sdktrace.ReadOnlySpan) error {
	r.Lock()
	defer r.Unlock()
	r.spans = append(r.spans, spans...)
	return nil
}

func (r *recordingExporter) Shutdown(ctx context.Context) error {
	return nil
}

// Verify default BatchSpanProcessor flushes in-flight spans on Shutdown.
func TestTelemetry_BatchProcessorShutdownFlush(t *testing.T) {
	exp := &recordingExporter{}
	// processor option is omitted to ensure BatchSpanProcessor is used
	tel, err := newTelemetry(context.Background(), Config{Endpoint: "http://otel.test:4318"}, options{exporter: exp})
	if err != nil {
		t.Fatalf("failed to create telemetry: %v", err)
	}

	handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
	handler.ServeHTTP(rec, req)

	// Shutdown must flush the batch span processor
	if err := tel.Shutdown(context.Background()); err != nil {
		t.Fatalf("expected nil error on shutdown, got %v", err)
	}

	exp.Lock()
	defer exp.Unlock()
	if len(exp.spans) != 1 {
		t.Fatalf("expected 1 flushed span after shutdown, got %d", len(exp.spans))
	}
	if exp.spans[0].Name() != "gateway.execution" {
		t.Fatalf("expected span name 'gateway.execution', got %q", exp.spans[0].Name())
	}
}

type failingExporter struct{}

func (failingExporter) ExportSpans(context.Context, []sdktrace.ReadOnlySpan) error {
	return errors.New("export failed")
}

func (failingExporter) Shutdown(context.Context) error {
	return errors.New("internal exporter shutdown network error: http://secret.internal:4318")
}

func TestTelemetry_SanitizedShutdownError(t *testing.T) {
	tel, err := newTelemetry(context.Background(), Config{Endpoint: "http://otel.test:4318"}, options{exporter: failingExporter{}})
	if err != nil {
		t.Fatalf("failed to create telemetry: %v", err)
	}

	err = tel.Shutdown(context.Background())
	if err == nil {
		t.Fatal("expected error from failing exporter")
	}
	if !errors.Is(err, ErrShutdownFailed) {
		t.Fatalf("expected ErrShutdownFailed, got %v", err)
	}
	// Verify raw internal exporter error string is not exposed
	if strings.Contains(err.Error(), "secret.internal") {
		t.Fatalf("raw error leaked through shutdown: %v", err)
	}
}

// POST-only execution tracing:
// Non-POST requests must delegate without creating spans.
func TestTelemetry_POSTOnly(t *testing.T) {
	for _, method := range []string{
		http.MethodGet,
		http.MethodHead,
		http.MethodPut,
		http.MethodDelete,
		http.MethodOptions,
		http.MethodPatch,
	} {
		t.Run(method, func(t *testing.T) {
			tel, exp := setupTestTelemetry(t)

			called := false
			handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				called = true
				w.WriteHeader(http.StatusMethodNotAllowed)
			}))

			req := httptest.NewRequest(method, executionRoute, nil)
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)

			if !called {
				t.Fatalf("[%s] expected next handler to be called", method)
			}
			if rec.Code != http.StatusMethodNotAllowed {
				t.Fatalf("[%s] expected status 405, got %d", method, rec.Code)
			}

			spans := exp.GetSpans()
			if len(spans) != 0 {
				t.Fatalf("[%s] non-POST request must produce 0 spans, got %d", method, len(spans))
			}
		})
	}
}

// Base endpoint URL derivation and validation edge tests.
func TestTelemetry_DeriveTracesURL(t *testing.T) {
	tests := []struct {
		name    string
		in      string
		want    string
		wantErr bool
	}{
		{
			name: "base endpoint without path",
			in:   "http://collector:4318",
			want: "http://collector:4318/v1/traces",
		},
		{
			name: "base endpoint with root slash",
			in:   "http://collector:4318/",
			want: "http://collector:4318/v1/traces",
		},
		{
			name: "base endpoint with subpath",
			in:   "https://collector.example/base",
			want: "https://collector.example/base/v1/traces",
		},
		{
			name: "base endpoint with subpath trailing slash",
			in:   "https://collector.example/base/",
			want: "https://collector.example/base/v1/traces",
		},
		{
			name: "bracketed IPv6",
			in:   "http://[::1]:4318",
			want: "http://[::1]:4318/v1/traces",
		},
		{
			name: "bracketed IPv6 with subpath",
			in:   "http://[2001:db8::1]:4318/telemetry",
			want: "http://[2001:db8::1]:4318/telemetry/v1/traces",
		},
		{
			name:    "empty string",
			in:      "",
			wantErr: true,
		},
		{
			name:    "whitespace only",
			in:      "   ",
			wantErr: true,
		},
		{
			name:    "surrounding whitespace",
			in:      " http://collector:4318 ",
			wantErr: true,
		},
		{
			name:    "internal whitespace",
			in:      "http://collector:4318 with spaces",
			wantErr: true,
		},
		{
			name:    "userinfo rejected",
			in:      "http://user:pass@collector:4318",
			wantErr: true,
		},
		{
			name:    "query rejected",
			in:      "http://collector:4318?token=secret",
			wantErr: true,
		},
		{
			name:    "fragment rejected",
			in:      "http://collector:4318#fragment",
			wantErr: true,
		},
		{
			name:    "opaque URL rejected",
			in:      "mailto:collector@example.com",
			wantErr: true,
		},
		{
			name:    "missing hostname rejected",
			in:      "http://:4318",
			wantErr: true,
		},
		{
			name:    "unsupported scheme ftp",
			in:      "ftp://collector:4318",
			wantErr: true,
		},
		{
			name:    "bad port non-numeric",
			in:      "http://collector:badport",
			wantErr: true,
		},
		{
			name:    "bad port zero",
			in:      "http://collector:0",
			wantErr: true,
		},
		{
			name:    "bad port out of range",
			in:      "http://collector:65536",
			wantErr: true,
		},
		{
			name:    "unclosed bracket IPv6",
			in:      "http://[::1:4318",
			wantErr: true,
		},
		{
			name:    "invalid bracketed IPv6",
			in:      "http://[not_an_ip]:4318",
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := deriveTracesURL(tc.in)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error for %q, got nil", tc.in)
				}
				return
			}
			if err != nil {
				t.Fatalf("expected nil error for %q, got: %v", tc.in, err)
			}
			if got != tc.want {
				t.Fatalf("deriveTracesURL(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

// ResponseWriter regression tests:
// - header forwarding unchanged;
// - response body bytes unchanged;
// - implicit 200;
// - first WriteHeader wins for recorded status;
// - repeated WriteHeader does not overwrite recorded status.
func TestTelemetry_ResponseWriter(t *testing.T) {
	t.Run("header forwarding and body unchanged", func(t *testing.T) {
		rec := httptest.NewRecorder()
		sw := &statusWriter{ResponseWriter: rec}

		sw.Header().Set("X-Custom-Header", "custom-value")
		sw.Header().Set("Content-Type", "application/json")
		sw.WriteHeader(http.StatusAccepted)

		payload := []byte(`{"result":"accepted"}`)
		n, err := sw.Write(payload)
		if err != nil {
			t.Fatalf("unexpected write error: %v", err)
		}
		if n != len(payload) {
			t.Fatalf("wrote %d bytes, want %d", n, len(payload))
		}

		if sw.status != http.StatusAccepted {
			t.Fatalf("expected recorded status %d, got %d", http.StatusAccepted, sw.status)
		}
		if rec.Code != http.StatusAccepted {
			t.Fatalf("expected recorder code %d, got %d", http.StatusAccepted, rec.Code)
		}
		if rec.Header().Get("X-Custom-Header") != "custom-value" {
			t.Fatalf("header not forwarded: got %q", rec.Header().Get("X-Custom-Header"))
		}
		if rec.Body.String() != string(payload) {
			t.Fatalf("body mismatch: got %q, want %q", rec.Body.String(), string(payload))
		}
	})

	t.Run("implicit 200 on write without WriteHeader", func(t *testing.T) {
		rec := httptest.NewRecorder()
		sw := &statusWriter{ResponseWriter: rec}

		payload := []byte("implicit ok")
		_, _ = sw.Write(payload)

		if sw.status != http.StatusOK {
			t.Fatalf("expected implicit status 200, got %d", sw.status)
		}
		if rec.Code != http.StatusOK {
			t.Fatalf("expected recorder code 200, got %d", rec.Code)
		}
		if rec.Body.String() != "implicit ok" {
			t.Fatalf("body mismatch: got %q", rec.Body.String())
		}
	})

	t.Run("first WriteHeader wins over subsequent WriteHeader", func(t *testing.T) {
		rec := httptest.NewRecorder()
		sw := &statusWriter{ResponseWriter: rec}

		sw.WriteHeader(http.StatusCreated)
		sw.WriteHeader(http.StatusInternalServerError)

		if sw.status != http.StatusCreated {
			t.Fatalf("first WriteHeader must win: got %d, want %d", sw.status, http.StatusCreated)
		}
	})

	t.Run("WriteHeader after Write does not overwrite implicit 200", func(t *testing.T) {
		rec := httptest.NewRecorder()
		sw := &statusWriter{ResponseWriter: rec}

		_, _ = sw.Write([]byte("data"))
		sw.WriteHeader(http.StatusBadRequest)

		if sw.status != http.StatusOK {
			t.Fatalf("write followed by WriteHeader must preserve 200: got %d, want 200", sw.status)
		}
	})
}

// Propagation edge tests:
// - invalid traceparent -> safe new root;
// - tracestate preserved only as propagation state, not recorded as attributes/events;
// - baggage header does not propagate through TraceContext-only propagator.
func TestTelemetry_PropagationEdges(t *testing.T) {
	t.Run("invalid traceparent creates safe new root", func(t *testing.T) {
		tel, exp := setupTestTelemetry(t)

		handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
		}))

		req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
		req.Header.Set("traceparent", "00-invalid-not-hex-trace-id-00000001-01")
		rec := httptest.NewRecorder()

		handler.ServeHTTP(rec, req)

		spans := exp.GetSpans()
		if len(spans) != 1 {
			t.Fatalf("expected 1 span, got %d", len(spans))
		}
		span := spans[0]
		if !span.SpanContext.TraceID().IsValid() {
			t.Fatal("expected valid trace ID on safe new root")
		}
		if span.Parent.SpanID().IsValid() {
			t.Fatal("expected no parent on invalid traceparent root span")
		}
	})

	t.Run("tracestate preserved only as propagation state not recorded as attributes or events", func(t *testing.T) {
		tel, exp := setupTestTelemetry(t)

		incomingTraceID := "4bf92f3577b34da6a3ce929d0e0e4736"
		incomingParentSpanID := "00f067aa0ba902b7"
		traceparent := fmt.Sprintf("00-%s-%s-01", incomingTraceID, incomingParentSpanID)
		tracestateVal := "rojo=opaque1,congo=opaque2"

		handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
		}))

		req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
		req.Header.Set("traceparent", traceparent)
		req.Header.Set("tracestate", tracestateVal)
		rec := httptest.NewRecorder()

		handler.ServeHTTP(rec, req)

		spans := exp.GetSpans()
		if len(spans) != 1 {
			t.Fatalf("expected 1 span, got %d", len(spans))
		}
		span := spans[0]

		// SpanContext preserves TraceState
		if span.SpanContext.TraceState().String() != tracestateVal {
			t.Fatalf("expected tracestate %q preserved in SpanContext, got %q", tracestateVal, span.SpanContext.TraceState().String())
		}

		// BUT tracestate tokens must NOT appear in attributes or events
		for _, a := range span.Attributes {
			k := string(a.Key)
			v := fmt.Sprintf("%v", a.Value.AsInterface())
			if strings.Contains(k, "rojo") || strings.Contains(k, "congo") || strings.Contains(k, "tracestate") ||
				strings.Contains(v, "opaque1") || strings.Contains(v, "opaque2") {
				t.Fatalf("tracestate leaked into span attribute: %s=%s", k, v)
			}
		}

		for _, ev := range span.Events {
			if strings.Contains(ev.Name, "tracestate") || strings.Contains(ev.Name, "rojo") {
				t.Fatalf("tracestate leaked into event name: %s", ev.Name)
			}
			for _, a := range ev.Attributes {
				if strings.Contains(string(a.Key), "rojo") || strings.Contains(fmt.Sprintf("%v", a.Value.AsInterface()), "opaque") {
					t.Fatalf("tracestate leaked into event attribute: %s=%v", a.Key, a.Value)
				}
			}
		}
	})

	t.Run("baggage does not propagate through TraceContext propagator", func(t *testing.T) {
		tel, exp := setupTestTelemetry(t)

		handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
		}))

		req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
		req.Header.Set("baggage", "userId=alice12345,secretKey=topsecret")
		rec := httptest.NewRecorder()

		handler.ServeHTTP(rec, req)

		spans := exp.GetSpans()
		if len(spans) != 1 {
			t.Fatalf("expected 1 span, got %d", len(spans))
		}
		span := spans[0]

		for _, a := range span.Attributes {
			k := string(a.Key)
			v := fmt.Sprintf("%v", a.Value.AsInterface())
			if strings.Contains(k, "userId") || strings.Contains(k, "secretKey") ||
				strings.Contains(v, "alice12345") || strings.Contains(v, "topsecret") {
				t.Fatalf("baggage leaked into span attribute: %s=%s", k, v)
			}
		}
	})
}

// Canary test proving raw collector failure details cannot escape through sanitizing exporter.
func TestTelemetry_SanitizingExporterCanary(t *testing.T) {
	const canarySecret = "CANARY_SECRET_COLLECTOR_FAILURE_12345"
	rawErr := fmt.Errorf("connection refused to http://%s.internal:4318: internal dial failure", canarySecret)

	inner := &canaryFailingExporter{
		exportErr:   rawErr,
		shutdownErr: rawErr,
	}

	sanitized := &sanitizingExporter{inner: inner}

	// 1. Direct ExportSpans returns sanitized sentinel
	expErr := sanitized.ExportSpans(context.Background(), nil)
	if expErr == nil {
		t.Fatal("expected error from ExportSpans")
	}
	if !errors.Is(expErr, ErrExportFailed) {
		t.Fatalf("expected ErrExportFailed, got: %v", expErr)
	}
	if strings.Contains(expErr.Error(), canarySecret) {
		t.Fatalf("raw error canary leaked through ExportSpans: %s", expErr.Error())
	}

	// 2. Direct Shutdown returns sanitized sentinel
	shutErr := sanitized.Shutdown(context.Background())
	if shutErr == nil {
		t.Fatal("expected error from Shutdown")
	}
	if !errors.Is(shutErr, ErrShutdownFailed) {
		t.Fatalf("expected ErrShutdownFailed, got: %v", shutErr)
	}
	if strings.Contains(shutErr.Error(), canarySecret) {
		t.Fatalf("raw error canary leaked through Shutdown: %s", shutErr.Error())
	}

	// 3. BatchSpanProcessor export failure does not leak canary to default log output
	var logBuf bytes.Buffer
	prevWriter := log.Writer()
	log.SetOutput(&logBuf)
	defer log.SetOutput(prevWriter)

	bsp := sdktrace.NewBatchSpanProcessor(sanitized,
		sdktrace.WithMaxQueueSize(2048),
		sdktrace.WithMaxExportBatchSize(512),
		sdktrace.WithBatchTimeout(5*time.Second),
		sdktrace.WithExportTimeout(30*time.Second),
	)

	tp := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(bsp))
	tr := tp.Tracer("test")
	_, span := tr.Start(context.Background(), "test-span")
	span.End()

	_ = tp.Shutdown(context.Background())

	capturedLog := logBuf.String()
	if strings.Contains(capturedLog, canarySecret) {
		t.Fatalf("canary secret leaked into log output: %s", capturedLog)
	}
}

type canaryFailingExporter struct {
	exportErr   error
	shutdownErr error
}

func (c *canaryFailingExporter) ExportSpans(ctx context.Context, spans []sdktrace.ReadOnlySpan) error {
	return c.exportErr
}

func (c *canaryFailingExporter) Shutdown(ctx context.Context) error {
	return c.shutdownErr
}

// Hostile environment with disabled telemetry:
// When telemetry endpoint is blank/whitespace:
// - set hostile unrelated OTel variables;
// - prove telemetry remains disabled;
// - zero exporter/provider/background activity;
// - wrapper delegates normally.
func TestTelemetry_DisabledHostileEnvironment(t *testing.T) {
	t.Setenv("OTEL_RESOURCE_ATTRIBUTES", "service.name=hostile-service")
	t.Setenv("OTEL_TRACES_SAMPLER", "always_on")
	t.Setenv("OTEL_BSP_SCHEDULE_DELAY", "1")
	t.Setenv("OTEL_BSP_MAX_QUEUE_SIZE", "99999")
	t.Setenv("OTEL_EXPORTER_OTLP_HEADERS", "hostile=header")
	t.Setenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://hostile:4318")

	for _, endpoint := range []string{"", "   ", "\t\n "} {
		tel, err := New(context.Background(), Config{Endpoint: endpoint})
		if err != nil {
			t.Fatalf("expected nil error on disabled config despite hostile env, got %v", err)
		}
		if tel.Enabled() {
			t.Fatal("expected telemetry to remain disabled")
		}

		called := false
		handler := tel.WrapExecutionHandler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			called = true
			w.WriteHeader(http.StatusOK)
		}))

		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, executionRoute, nil)
		handler.ServeHTTP(rec, req)

		if !called {
			t.Fatal("expected delegate handler to be called")
		}
		if rec.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d", rec.Code)
		}

		if err := tel.Shutdown(context.Background()); err != nil {
			t.Fatalf("expected nil error on shutdown, got %v", err)
		}
	}
}
