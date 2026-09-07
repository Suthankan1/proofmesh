package main

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"testing"
	"time"

	httpingress "github.com/Suthankan1/proofmesh/apps/gateway/internal/ingress/http"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/runtime"
)

type fakeRuntime struct {
	enforcer *pep.Enforcer
	closed   bool
	mu       sync.Mutex
	actions  *[]string
	actionMu *sync.Mutex
}

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

// A. Config failure:
// config load fails -> runtime not built -> server not started
func TestRun_ConfigFailure(t *testing.T) {
	runtimeBuilt := false
	serverStarted := false

	deps := serverDeps{
		loadConfig: func() (runtime.Config, error) {
			return runtime.Config{}, errors.New("missing environment variable")
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			runtimeBuilt = true
			return nil, nil
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return nil, nil
		},
		newServer: func(addr string, h http.Handler) httpServer {
			serverStarted = true
			return nil
		},
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrConfigLoadFailed) {
		t.Fatalf("expected ErrConfigLoadFailed, got %v", err)
	}
	if runtimeBuilt {
		t.Fatal("expected runtime not to be built on config failure")
	}
	if serverStarted {
		t.Fatal("expected server not to be started on config failure")
	}
}

// B. Runtime construction failure:
// runtime build fails -> handler/server not started
func TestRun_RuntimeConstructionFailure(t *testing.T) {
	handlerBuilt := false
	serverStarted := false

	deps := serverDeps{
		loadConfig: func() (runtime.Config, error) {
			return runtime.Config{}, nil
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return nil, errors.New("database unavailable")
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			handlerBuilt = true
			return nil, nil
		},
		newServer: func(addr string, h http.Handler) httpServer {
			serverStarted = true
			return nil
		},
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrRuntimeInitFailed) {
		t.Fatalf("expected ErrRuntimeInitFailed, got %v", err)
	}
	if handlerBuilt {
		t.Fatal("expected handler not to be built on runtime construction failure")
	}
	if serverStarted {
		t.Fatal("expected server not to be started on runtime construction failure")
	}
}

// C. Handler construction failure:
// runtime exists -> handler build fails -> runtime closes
func TestRun_HandlerConstructionFailure(t *testing.T) {
	rt := &fakeRuntime{}
	serverStarted := false

	deps := serverDeps{
		loadConfig: func() (runtime.Config, error) {
			return runtime.Config{}, nil
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return rt, nil
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return nil, errors.New("handler initialization failed")
		},
		newServer: func(addr string, h http.Handler) httpServer {
			serverStarted = true
			return nil
		},
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrHandlerInitFailed) {
		t.Fatalf("expected ErrHandlerInitFailed, got %v", err)
	}
	if !rt.closed {
		t.Fatal("expected runtime to be closed when handler construction fails")
	}
	if serverStarted {
		t.Fatal("expected server not to be started when handler construction fails")
	}
}

// Handler construction failure with real httpingress.NewHandler(nil)
func TestRun_RealHandlerConstructionFailure(t *testing.T) {
	rt := &fakeRuntime{enforcer: nil}

	deps := serverDeps{
		loadConfig: func() (runtime.Config, error) {
			return runtime.Config{}, nil
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return rt, nil
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return httpingress.NewHandler(enforcer)
		},
		newServer: func(addr string, h http.Handler) httpServer {
			return nil
		},
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrHandlerInitFailed) {
		t.Fatalf("expected ErrHandlerInitFailed, got %v", err)
	}
	if !rt.closed {
		t.Fatal("expected runtime to be closed on real handler construction failure")
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
// Assert: server Shutdown BEFORE runtime Close
func TestRun_GracefulShutdownOrdering(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	var actions []string
	var actionMu sync.Mutex

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

	deps := serverDeps{
		loadConfig: func() (runtime.Config, error) {
			return runtime.Config{}, nil
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return rt, nil
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return http.NotFoundHandler(), nil
		},
		newServer: func(addr string, h http.Handler) httpServer {
			return srv
		},
		shutdownTimeout: 5 * time.Second,
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
	for i, a := range actions {
		if a == "server:shutdown:complete" {
			shutdownCompleteIdx = i
		}
		if a == "runtime:close" {
			runtimeCloseIdx = i
		}
	}

	if shutdownCompleteIdx == -1 {
		t.Fatal("server:shutdown:complete was not recorded")
	}
	if runtimeCloseIdx == -1 {
		t.Fatal("runtime:close was not recorded")
	}
	if shutdownCompleteIdx >= runtimeCloseIdx {
		t.Fatalf("expected server shutdown to complete BEFORE runtime close, but actions were: %v", actions)
	}
}

// F. Shutdown failure:
// If Shutdown fails: force Close called; runtime still closes; run returns failure.
func TestRun_ShutdownFailure(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	var actions []string
	var actionMu sync.Mutex

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

	deps := serverDeps{
		loadConfig: func() (runtime.Config, error) {
			return runtime.Config{}, nil
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return rt, nil
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return http.NotFoundHandler(), nil
		},
		newServer: func(addr string, h http.Handler) httpServer {
			return srv
		},
		shutdownTimeout: 5 * time.Second,
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
}

// G. Serve failure:
// Non-http.ErrServerClosed: runtime closes; sanitized failure returned.
func TestRun_ServeFailure(t *testing.T) {
	rt := &fakeRuntime{}
	listenDone := make(chan struct{})
	close(listenDone)

	srv := &fakeServer{
		listenErr:  errors.New("network address already in use"),
		listenDone: listenDone,
	}

	deps := serverDeps{
		loadConfig: func() (runtime.Config, error) {
			return runtime.Config{}, nil
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return rt, nil
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return http.NotFoundHandler(), nil
		},
		newServer: func(addr string, h http.Handler) httpServer {
			return srv
		},
	}

	err := runWithDeps(context.Background(), deps)
	if !errors.Is(err, ErrServerExited) {
		t.Fatalf("expected ErrServerExited, got %v", err)
	}
	if !rt.closed {
		t.Fatal("expected runtime to be closed when serve fails")
	}
}

// H. Normal server close:
// Expected http.ErrServerClosed during shutdown must not be treated as an unrelated failure.
func TestRun_NormalServerClose(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	rt := &fakeRuntime{}

	srv := &fakeServer{
		listenErr:  http.ErrServerClosed,
		listenDone: make(chan struct{}),
	}

	deps := serverDeps{
		loadConfig: func() (runtime.Config, error) {
			return runtime.Config{}, nil
		},
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return rt, nil
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return http.NotFoundHandler(), nil
		},
		newServer: func(addr string, h http.Handler) httpServer {
			return srv
		},
		shutdownTimeout: 5 * time.Second,
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
}
