package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/appconfig"
	httpingress "github.com/Suthankan1/proofmesh/apps/gateway/internal/ingress/http"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/runtime"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/telemetry"
)

type fakeRuntime struct {
	enforcer *pep.Enforcer
	closed   bool
	mu       sync.Mutex
	actions  *[]string
	actionMu *sync.Mutex
}

func (f *fakeRuntime) Ready(context.Context) error { return nil }

func (f *fakeRuntime) Enforcer() *pep.Enforcer {
	return f.enforcer
}

func (f *fakeRuntime) Close() {
	f.mu.Lock()
	f.closed = true
	f.mu.Unlock()
	if f.actions != nil && f.actionMu != nil {
		f.actionMu.Lock()
		*f.actions = append(*f.actions, "runtime:close")
		f.actionMu.Unlock()
	}
}

type fakeTelemetry struct {
	wrapped       bool
	shutdownErr   error
	shutdownCalls int
	mu            sync.Mutex
	actions       *[]string
	actionMu      *sync.Mutex
}

func (f *fakeTelemetry) WrapExecutionHandler(next http.Handler) http.Handler {
	f.mu.Lock()
	f.wrapped = true
	f.mu.Unlock()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if f.actions != nil && f.actionMu != nil {
			f.actionMu.Lock()
			*f.actions = append(*f.actions, "telemetry:wrap:serve")
			f.actionMu.Unlock()
		}
		next.ServeHTTP(w, r)
	})
}

func (f *fakeTelemetry) Shutdown(ctx context.Context) error {
	f.mu.Lock()
	f.shutdownCalls++
	f.mu.Unlock()
	if f.actions != nil && f.actionMu != nil {
		f.actionMu.Lock()
		*f.actions = append(*f.actions, "telemetry:shutdown")
		f.actionMu.Unlock()
	}
	return f.shutdownErr
}

type fakeServer struct {
	listenErr   error
	shutdownErr error
	closeCalled bool
	listenDone  chan struct{}
	mu          sync.Mutex
	actions     *[]string
	actionMu    *sync.Mutex
}

func (s *fakeServer) record(action string) {
	if s.actions != nil && s.actionMu != nil {
		s.actionMu.Lock()
		*s.actions = append(*s.actions, action)
		s.actionMu.Unlock()
	}
}

func (s *fakeServer) ListenAndServe() error {
	<-s.listenDone
	return s.listenErr
}

func (s *fakeServer) Shutdown(ctx context.Context) error {
	s.record("server:shutdown:start")
	close(s.listenDone)
	s.record("server:shutdown:complete")
	return s.shutdownErr
}

func (s *fakeServer) Close() error {
	s.mu.Lock()
	s.closeCalled = true
	s.mu.Unlock()
	s.record("server:close")
	return nil
}

func baseTestDeps() serverDeps {
	return serverDeps{
		loadConfig: func() (appconfig.Config, error) {
			return appconfig.Config{}, nil
		},
		newTelemetry: func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
			return &fakeTelemetry{}, nil
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return &fakeRuntime{}, nil
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return http.NotFoundHandler(), nil
		},
		newServer: func(addr string, h http.Handler) httpServer {
			return &fakeServer{listenDone: make(chan struct{})}
		},
		shutdownTimeout:  5 * time.Second,
		telemetryTimeout: 5 * time.Second,
	}
}

// A. Config failure:
// config load fails -> telemetry/runtime not built -> server not started
func TestRun_ConfigFailure(t *testing.T) {
	telemetryBuilt := false
	runtimeBuilt := false
	serverStarted := false

	deps := baseTestDeps()
	deps.loadConfig = func() (appconfig.Config, error) {
		return appconfig.Config{}, errors.New("missing environment variable")
	}
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		telemetryBuilt = true
		return &fakeTelemetry{}, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		runtimeBuilt = true
		return &fakeRuntime{}, nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		serverStarted = true
		return nil
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrConfigLoadFailed) {
		t.Fatalf("expected ErrConfigLoadFailed, got %v", err)
	}
	if telemetryBuilt {
		t.Fatal("expected telemetry not to be built on config failure")
	}
	if runtimeBuilt {
		t.Fatal("expected runtime not to be built on config failure")
	}
	if serverStarted {
		t.Fatal("expected server not to be started on config failure")
	}
}

// Telemetry init failure:
// telemetry build fails -> runtime/handler/server not built
func TestRun_TelemetryInitFailure(t *testing.T) {
	runtimeBuilt := false
	serverStarted := false

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return nil, errors.New("telemetry initialization error")
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		runtimeBuilt = true
		return &fakeRuntime{}, nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		serverStarted = true
		return nil
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrTelemetryInitFailed) {
		t.Fatalf("expected ErrTelemetryInitFailed, got %v", err)
	}
	if runtimeBuilt {
		t.Fatal("expected runtime not to be built on telemetry init failure")
	}
	if serverStarted {
		t.Fatal("expected server not to be started on telemetry init failure")
	}
}

// B. Runtime construction failure:
// runtime build fails -> telemetry shutdown called -> handler/server not started
func TestRun_RuntimeConstructionFailure(t *testing.T) {
	tel := &fakeTelemetry{}
	handlerBuilt := false
	serverStarted := false

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		return nil, errors.New("database unavailable")
	}
	deps.newHandler = func(enforcer *pep.Enforcer) (http.Handler, error) {
		handlerBuilt = true
		return nil, nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		serverStarted = true
		return nil
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrRuntimeInitFailed) {
		t.Fatalf("expected ErrRuntimeInitFailed, got %v", err)
	}
	if tel.shutdownCalls != 1 {
		t.Fatalf("expected telemetry shutdown to be called once on runtime construction failure, got %d", tel.shutdownCalls)
	}
	if handlerBuilt {
		t.Fatal("expected handler not to be built on runtime construction failure")
	}
	if serverStarted {
		t.Fatal("expected server not to be started on runtime construction failure")
	}
}

// C. Handler construction failure:
// runtime exists -> handler build fails -> runtime closes, telemetry shuts down
func TestRun_HandlerConstructionFailure(t *testing.T) {
	tel := &fakeTelemetry{}
	rt := &fakeRuntime{}
	serverStarted := false

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		return rt, nil
	}
	deps.newHandler = func(enforcer *pep.Enforcer) (http.Handler, error) {
		return nil, errors.New("handler initialization failed")
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		serverStarted = true
		return nil
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrHandlerInitFailed) {
		t.Fatalf("expected ErrHandlerInitFailed, got %v", err)
	}
	if !rt.closed {
		t.Fatal("expected runtime to be closed when handler construction fails")
	}
	if tel.shutdownCalls != 1 {
		t.Fatalf("expected telemetry shutdown to be called once when handler construction fails, got %d", tel.shutdownCalls)
	}
	if serverStarted {
		t.Fatal("expected server not to be started when handler construction fails")
	}
}

// Handler construction failure with real httpingress.NewHandler(nil)
func TestRun_RealHandlerConstructionFailure(t *testing.T) {
	tel := &fakeTelemetry{}
	rt := &fakeRuntime{enforcer: nil}

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		return rt, nil
	}
	deps.newHandler = func(enforcer *pep.Enforcer) (http.Handler, error) {
		return httpingress.NewHandler(enforcer)
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrHandlerInitFailed) {
		t.Fatalf("expected ErrHandlerInitFailed, got %v", err)
	}
	if !rt.closed {
		t.Fatal("expected runtime to be closed on real handler construction failure")
	}
	if tel.shutdownCalls != 1 {
		t.Fatalf("expected telemetry shutdown to be called once on real handler failure, got %d", tel.shutdownCalls)
	}
}

type dummyHandler struct{}

func (dummyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {}

// D. Server configuration:
// Assert exact listen address and finite nonzero timeouts
func TestDefaultNewServer_Configuration(t *testing.T) {
	dummy := &dummyHandler{}
	srv := defaultNewServer(defaultListenAddress, dummy)

	if srv.Addr != ":8080" {
		t.Fatalf("expected Addr :8080, got %s", srv.Addr)
	}
	if srv.Handler != dummy {
		t.Fatal("expected handler to match")
	}
	if srv.ReadHeaderTimeout != 5*time.Second {
		t.Fatalf("expected ReadHeaderTimeout 5s, got %v", srv.ReadHeaderTimeout)
	}
	if srv.ReadTimeout != 15*time.Second {
		t.Fatalf("expected ReadTimeout 15s, got %v", srv.ReadTimeout)
	}
	if srv.WriteTimeout != 30*time.Second {
		t.Fatalf("expected WriteTimeout 30s, got %v", srv.WriteTimeout)
	}
	if srv.IdleTimeout != 60*time.Second {
		t.Fatalf("expected IdleTimeout 60s, got %v", srv.IdleTimeout)
	}
}

// E. Graceful shutdown ordering — CRITICAL:
// Assert: server.Shutdown -> wait ListenAndServe exit -> runtime.Close -> telemetry.Shutdown
func TestRun_GracefulShutdownOrdering(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	var actions []string
	var actionMu sync.Mutex

	tel := &fakeTelemetry{
		actions:  &actions,
		actionMu: &actionMu,
	}

	rt := &fakeRuntime{
		actions:  &actions,
		actionMu: &actionMu,
	}

	srv := &fakeServer{
		listenErr:  http.ErrServerClosed,
		listenDone: make(chan struct{}),
		actions:    &actions,
		actionMu:   &actionMu,
	}

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		return rt, nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		return srv
	}

	done := make(chan error, 1)
	go func() {
		done <- runWithDeps(ctx, deps)
	}()

	time.Sleep(20 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("expected nil error on clean shutdown, got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("runWithDeps timed out during shutdown")
	}

	actionMu.Lock()
	defer actionMu.Unlock()

	shutdownCompleteIdx := -1
	runtimeCloseIdx := -1
	telemetryShutdownIdx := -1
	for i, a := range actions {
		if a == "server:shutdown:complete" {
			shutdownCompleteIdx = i
		}
		if a == "runtime:close" {
			runtimeCloseIdx = i
		}
		if a == "telemetry:shutdown" {
			telemetryShutdownIdx = i
		}
	}

	if shutdownCompleteIdx == -1 {
		t.Fatal("server:shutdown:complete was not recorded")
	}
	if runtimeCloseIdx == -1 {
		t.Fatal("runtime:close was not recorded")
	}
	if telemetryShutdownIdx == -1 {
		t.Fatal("telemetry:shutdown was not recorded")
	}

	if shutdownCompleteIdx >= runtimeCloseIdx {
		t.Fatalf("expected server shutdown before runtime close: actions=%v", actions)
	}
	if runtimeCloseIdx >= telemetryShutdownIdx {
		t.Fatalf("expected runtime close before telemetry shutdown: actions=%v", actions)
	}
	if tel.shutdownCalls != 1 {
		t.Fatalf("expected telemetry shutdown to be called exactly once, got %d", tel.shutdownCalls)
	}
}

// F. Shutdown failure:
// If Shutdown fails: force Close called; runtime still closes; telemetry shuts down; run returns failure.
func TestRun_ShutdownFailure(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	var actions []string
	var actionMu sync.Mutex

	tel := &fakeTelemetry{
		actions:  &actions,
		actionMu: &actionMu,
	}

	rt := &fakeRuntime{
		actions:  &actions,
		actionMu: &actionMu,
	}

	srv := &fakeServer{
		listenErr:   http.ErrServerClosed,
		listenDone:  make(chan struct{}),
		shutdownErr: errors.New("context deadline exceeded"),
		actions:     &actions,
		actionMu:    &actionMu,
	}

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		return rt, nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		return srv
	}

	done := make(chan error, 1)
	go func() {
		done <- runWithDeps(ctx, deps)
	}()

	time.Sleep(20 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if !errors.Is(err, ErrShutdownFailed) {
			t.Fatalf("expected ErrShutdownFailed, got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("runWithDeps timed out during shutdown failure test")
	}

	if !srv.closeCalled {
		t.Fatal("expected srv.Close() to be called on shutdown failure")
	}
	if !rt.closed {
		t.Fatal("expected runtime.Close() to be called on shutdown failure")
	}
	if tel.shutdownCalls != 1 {
		t.Fatalf("expected telemetry shutdown called once, got %d", tel.shutdownCalls)
	}
}

// Telemetry shutdown failure:
// If telemetry shutdown fails: ErrShutdownFailed returned.
func TestRun_TelemetryShutdownFailure(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())

	tel := &fakeTelemetry{
		shutdownErr: errors.New("telemetry shutdown timeout"),
	}
	rt := &fakeRuntime{}
	srv := &fakeServer{
		listenErr:  http.ErrServerClosed,
		listenDone: make(chan struct{}),
	}

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		return rt, nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		return srv
	}

	done := make(chan error, 1)
	go func() {
		done <- runWithDeps(ctx, deps)
	}()

	time.Sleep(20 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if !errors.Is(err, ErrShutdownFailed) {
			t.Fatalf("expected ErrShutdownFailed on telemetry shutdown error, got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("runWithDeps timed out")
	}

	if !rt.closed {
		t.Fatal("expected runtime.Close() to be called")
	}
	if tel.shutdownCalls != 1 {
		t.Fatalf("expected telemetry shutdown called once, got %d", tel.shutdownCalls)
	}
}

// G. Serve failure:
// Non-http.ErrServerClosed: runtime closes; telemetry shuts down; sanitized failure returned.
func TestRun_ServeFailure(t *testing.T) {
	tel := &fakeTelemetry{}
	rt := &fakeRuntime{}
	listenDone := make(chan struct{})
	close(listenDone)

	srv := &fakeServer{
		listenErr:  errors.New("network address already in use"),
		listenDone: listenDone,
	}

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		return rt, nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		return srv
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrServerExited) {
		t.Fatalf("expected ErrServerExited, got %v", err)
	}
	if !rt.closed {
		t.Fatal("expected runtime to be closed when serve fails")
	}
	if tel.shutdownCalls != 1 {
		t.Fatalf("expected telemetry shutdown called once, got %d", tel.shutdownCalls)
	}
}

// H. Normal server close:
// Expected http.ErrServerClosed during shutdown must not be treated as an unrelated failure.
func TestRun_NormalServerClose(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	tel := &fakeTelemetry{}
	rt := &fakeRuntime{}

	srv := &fakeServer{
		listenErr:  http.ErrServerClosed,
		listenDone: make(chan struct{}),
	}

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newRuntime = func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
		return rt, nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		return srv
	}

	done := make(chan error, 1)
	go func() {
		done <- runWithDeps(ctx, deps)
	}()

	time.Sleep(20 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("expected nil error on normal shutdown with http.ErrServerClosed, got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("runWithDeps timed out during normal shutdown test")
	}

	if !rt.closed {
		t.Fatal("expected runtime to be closed on shutdown")
	}
	if tel.shutdownCalls != 1 {
		t.Fatalf("expected telemetry shutdown called once, got %d", tel.shutdownCalls)
	}
}

// Execution route is wrapped with telemetry, but health and readiness are not.
func TestRun_ExecutionRouteTracedAndProbesNotTraced(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var actions []string
	var actionMu sync.Mutex

	tel := &fakeTelemetry{
		actions:  &actions,
		actionMu: &actionMu,
	}

	capturedRoutesChan := make(chan http.Handler, 1)
	srv := &fakeServer{
		listenErr:  http.ErrServerClosed,
		listenDone: make(chan struct{}),
	}

	deps := baseTestDeps()
	deps.newTelemetry = func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
		return tel, nil
	}
	deps.newHandler = func(enforcer *pep.Enforcer) (http.Handler, error) {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
		}), nil
	}
	deps.newServer = func(addr string, h http.Handler) httpServer {
		capturedRoutesChan <- h
		return srv
	}

	done := make(chan error, 1)
	go func() {
		done <- runWithDeps(ctx, deps)
	}()

	var capturedRoutes http.Handler
	select {
	case capturedRoutes = <-capturedRoutesChan:
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for server routes handler")
	}

	// 1. POST /v1/executions must pass through telemetry wrapper
	wExec := httptest.NewRecorder()
	reqExec := httptest.NewRequest("POST", "/v1/executions", nil)
	capturedRoutes.ServeHTTP(wExec, reqExec)

	actionMu.Lock()
	execCount := 0
	for _, a := range actions {
		if a == "telemetry:wrap:serve" {
			execCount++
		}
	}
	actionMu.Unlock()

	if execCount != 1 {
		t.Fatalf("expected 1 telemetry:wrap:serve call for execution route, got %d", execCount)
	}

	// 2. GET /healthz must NOT invoke telemetry wrapper
	wHealth := httptest.NewRecorder()
	reqHealth := httptest.NewRequest("GET", "/healthz", nil)
	capturedRoutes.ServeHTTP(wHealth, reqHealth)

	// 3. GET /readyz must NOT invoke telemetry wrapper
	wReady := httptest.NewRecorder()
	reqReady := httptest.NewRequest("GET", "/readyz", nil)
	capturedRoutes.ServeHTTP(wReady, reqReady)

	actionMu.Lock()
	finalCount := 0
	for _, a := range actions {
		if a == "telemetry:wrap:serve" {
			finalCount++
		}
	}
	actionMu.Unlock()

	if finalCount != 1 {
		t.Fatalf("probes must not invoke telemetry wrapper: count was %d", finalCount)
	}

	cancel()
	<-done
}

func TestHealthReadinessRoutes(t *testing.T) {
	for _, tc := range []struct {
		name, method, path string
		failure            bool
		code               int
		body               string
		calls              int
	}{
		{"health", "GET", "/healthz", true, 200, "{\"status\":\"ok\"}\n", 0},
		{"ready", "GET", "/readyz", false, 200, "{\"status\":\"ready\"}\n", 1},
		{"unavailable", "GET", "/readyz", true, 503, "{\"status\":\"not_ready\"}\n", 1},
		{"health method", "POST", "/healthz", false, 405, "", 0},
		{"ready method", "POST", "/readyz", false, 405, "", 0},
		{"health HEAD", "HEAD", "/healthz", false, 405, "{\"status\":\"method_not_allowed\"}\n", 0},
		{"ready HEAD", "HEAD", "/readyz", false, 405, "{\"status\":\"method_not_allowed\"}\n", 0},
		{"unknown", "GET", "/unknown", false, 404, "", 0},
		{"health suffix", "GET", "/healthz/extra", false, 404, "", 0},
		{"ready suffix", "GET", "/readyz/extra", false, 404, "", 0},
		{"execution suffix", "POST", "/v1/executions/extra", false, 404, "", 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			executionCalls := 0
			execHandler := http.HandlerFunc(func(http.ResponseWriter, *http.Request) { executionCalls++ })
			h := newRoutes(execHandler, execHandler, func(ctx context.Context) error {
				calls++
				deadline, ok := ctx.Deadline()
				if !ok || time.Until(deadline) > readinessTimeout {
					t.Fatal("missing bounded deadline")
				}
				if tc.failure {
					return errors.New("postgres://secret@canary-db:5432/internal")
				}
				return nil
			})
			w := httptest.NewRecorder()
			h.ServeHTTP(w, httptest.NewRequest(tc.method, tc.path, nil))
			if w.Code != tc.code || calls != tc.calls {
				t.Fatalf("code=%d calls=%d", w.Code, calls)
			}
			if executionCalls != 0 {
				t.Fatalf("probe reached execution: calls=%d", executionCalls)
			}
			if tc.method == http.MethodHead && w.Header().Get("Allow") != http.MethodGet {
				t.Fatalf("expected Allow: GET, got %q", w.Header().Get("Allow"))
			}
			if tc.body != "" {
				assertProbe(t, w, tc.code, tc.body)
			}
		})
	}
}

func assertProbe(t *testing.T, w *httptest.ResponseRecorder, code int, body string) {
	t.Helper()
	if w.Code != code || w.Body.String() != body || w.Header().Get("Content-Type") != "application/json" || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("unexpected probe response: %d %v %q", w.Code, w.Header(), w.Body.String())
	}
}

func TestReadinessTimeoutAndCanceled(t *testing.T) {
	for _, canceled := range []bool{false, true} {
		t.Run(map[bool]string{false: "timeout", true: "canceled"}[canceled], func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if canceled {
				cancel()
			}
			returned := false
			h := newRoutes(http.NotFoundHandler(), http.NotFoundHandler(), func(probe context.Context) error {
				<-probe.Done()
				returned = true
				return probe.Err()
			})
			w := httptest.NewRecorder()
			start := time.Now()
			h.ServeHTTP(w, httptest.NewRequest("GET", "/readyz", nil).WithContext(ctx))
			elapsed := time.Since(start)
			if !returned || elapsed > readinessTimeout+time.Second {
				t.Fatal("readiness did not finish within bound")
			}
			if canceled && elapsed > time.Second {
				t.Fatal("request cancellation was not propagated")
			}
			assertProbe(t, w, 503, "{\"status\":\"not_ready\"}\n")
		})
	}
}

func TestExecutionRouteDelegation(t *testing.T) {
	ingress, err := httpingress.NewHandler(&pep.Enforcer{})
	if err != nil {
		t.Fatal(err)
	}
	for _, method := range []string{"POST", "GET", "PUT", "HEAD", "OPTIONS"} {
		t.Run(method, func(t *testing.T) {
			req := httptest.NewRequest(method, httpingress.ExecutionEndpoint, nil)
			direct := httptest.NewRecorder()
			ingress.ServeHTTP(direct, req)
			calls := 0
			handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				if r != req {
					t.Fatal("execution request replaced")
				}
				ingress.ServeHTTP(w, r)
			})
			routes := newRoutes(handler, handler, func(context.Context) error { t.Fatal("execution checked readiness"); return nil })
			got := httptest.NewRecorder()
			routes.ServeHTTP(got, req)
			if calls != 1 || got.Code != direct.Code || got.Body.String() != direct.Body.String() || got.Header().Get("Allow") != direct.Header().Get("Allow") {
				t.Fatalf("execution ingress behavior changed: %d %q", got.Code, got.Body.String())
			}
		})
	}
}

// Direct span-count and routing tests:
// POST /v1/executions    -> exactly 1 execution span
// GET /v1/executions     -> 0
// HEAD /v1/executions    -> 0
// PUT /v1/executions     -> 0
// OPTIONS /v1/executions -> 0
// GET /healthz           -> 0
// GET /readyz            -> 0
// unknown path           -> 0
// Non-POST execution responses must still match direct ingress behavior.
func TestRoutes_DirectSpanCountsAndIngressBehavior(t *testing.T) {
	ingress, err := httpingress.NewHandler(&pep.Enforcer{})
	if err != nil {
		t.Fatal(err)
	}

	spansCreated := 0
	tracedHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		spansCreated++
		ingress.ServeHTTP(w, r)
	})
	untracedHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ingress.ServeHTTP(w, r)
	})

	routes := newRoutes(tracedHandler, untracedHandler, func(context.Context) error { return nil })

	tests := []struct {
		name       string
		method     string
		path       string
		wantSpans  int
		wantStatus int
		wantAllow  string
	}{
		{
			name:       "POST executions creates exactly 1 span",
			method:     http.MethodPost,
			path:       "/v1/executions",
			wantSpans:  1,
			wantStatus: http.StatusUnauthorized, // pep.Enforcer with nil verifier returns 401 Unauthorized
		},
		{
			name:       "GET executions creates 0 spans and returns 405",
			method:     http.MethodGet,
			path:       "/v1/executions",
			wantSpans:  0,
			wantStatus: http.StatusMethodNotAllowed,
			wantAllow:  http.MethodPost,
		},
		{
			name:       "HEAD executions creates 0 spans and returns 405",
			method:     http.MethodHead,
			path:       "/v1/executions",
			wantSpans:  0,
			wantStatus: http.StatusMethodNotAllowed,
			wantAllow:  http.MethodPost,
		},
		{
			name:       "PUT executions creates 0 spans and returns 405",
			method:     http.MethodPut,
			path:       "/v1/executions",
			wantSpans:  0,
			wantStatus: http.StatusMethodNotAllowed,
			wantAllow:  http.MethodPost,
		},
		{
			name:       "OPTIONS executions creates 0 spans and returns 405",
			method:     http.MethodOptions,
			path:       "/v1/executions",
			wantSpans:  0,
			wantStatus: http.StatusMethodNotAllowed,
			wantAllow:  http.MethodPost,
		},
		{
			name:       "GET healthz creates 0 spans and returns 200",
			method:     http.MethodGet,
			path:       "/healthz",
			wantSpans:  0,
			wantStatus: http.StatusOK,
		},
		{
			name:       "GET readyz creates 0 spans and returns 200",
			method:     http.MethodGet,
			path:       "/readyz",
			wantSpans:  0,
			wantStatus: http.StatusOK,
		},
		{
			name:       "unknown path creates 0 spans and returns 404",
			method:     http.MethodGet,
			path:       "/unknown",
			wantSpans:  0,
			wantStatus: http.StatusNotFound,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			beforeSpans := spansCreated

			req := httptest.NewRequest(tc.method, tc.path, nil)
			rec := httptest.NewRecorder()
			routes.ServeHTTP(rec, req)

			deltaSpans := spansCreated - beforeSpans
			if deltaSpans != tc.wantSpans {
				t.Fatalf("expected %d spans, got %d", tc.wantSpans, deltaSpans)
			}
			if rec.Code != tc.wantStatus {
				t.Fatalf("expected status %d, got %d", tc.wantStatus, rec.Code)
			}
			if tc.wantAllow != "" && rec.Header().Get("Allow") != tc.wantAllow {
				t.Fatalf("expected Allow: %q, got %q", tc.wantAllow, rec.Header().Get("Allow"))
			}

			// If it's an execution route, also verify against direct ingress
			if tc.path == "/v1/executions" {
				directRec := httptest.NewRecorder()
				directReq := httptest.NewRequest(tc.method, tc.path, nil)
				ingress.ServeHTTP(directRec, directReq)

				if rec.Code != directRec.Code {
					t.Fatalf("status mismatch with direct ingress: got %d, want %d", rec.Code, directRec.Code)
				}
				if rec.Body.String() != directRec.Body.String() {
					t.Fatalf("body mismatch with direct ingress: got %q, want %q", rec.Body.String(), directRec.Body.String())
				}
				if rec.Header().Get("Allow") != directRec.Header().Get("Allow") {
					t.Fatalf("Allow header mismatch with direct ingress: got %q, want %q", rec.Header().Get("Allow"), directRec.Header().Get("Allow"))
				}
			}
		})
	}
}
