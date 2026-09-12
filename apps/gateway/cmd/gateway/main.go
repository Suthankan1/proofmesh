package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/appconfig"
	httpingress "github.com/Suthankan1/proofmesh/apps/gateway/internal/ingress/http"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/runtime"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/telemetry"
)

const (
	defaultListenAddress     = ":8080"
	defaultReadHeaderTimeout = 5 * time.Second
	defaultReadTimeout       = 15 * time.Second
	defaultWriteTimeout      = 30 * time.Second
	defaultIdleTimeout       = 60 * time.Second
	defaultShutdownTimeout   = 10 * time.Second
	readinessTimeout         = 2 * time.Second
)

// Sanitized sentinel errors returned by gateway application bootstrap and lifecycle.
// Infrastructure details, connection strings, credentials, and internal errors
// are never logged or leaked across this boundary.
var (
	ErrConfigLoadFailed    = errors.New("gateway: failed to load configuration")
	ErrTelemetryInitFailed = errors.New("gateway: failed to initialize telemetry")
	ErrRuntimeInitFailed   = errors.New("gateway: failed to initialize runtime")
	ErrHandlerInitFailed   = errors.New("gateway: failed to initialize handler")
	ErrServerExited        = errors.New("gateway: server exited unexpectedly")
	ErrShutdownFailed      = errors.New("gateway: graceful shutdown failed")
	ErrShutdownServerError = errors.New("gateway: server error during shutdown")
)

type gatewayRuntime interface {
	Enforcer() *pep.Enforcer
	Ready(context.Context) error
	Close()
}

type gatewayTelemetry interface {
	WrapExecutionHandler(http.Handler) http.Handler
	Shutdown(context.Context) error
}

type httpServer interface {
	ListenAndServe() error
	Shutdown(ctx context.Context) error
	Close() error
}

type serverDeps struct {
	loadConfig       func() (appconfig.Config, error)
	newRuntime       func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error)
	newTelemetry     func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error)
	newHandler       func(enforcer *pep.Enforcer) (http.Handler, error)
	newServer        func(addr string, handler http.Handler) httpServer
	shutdownTimeout  time.Duration
	telemetryTimeout time.Duration
}

func defaultDeps() serverDeps {
	return serverDeps{
		loadConfig: appconfig.Load,
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return runtime.New(ctx, cfg)
		},
		newTelemetry: func(ctx context.Context, cfg telemetry.Config) (gatewayTelemetry, error) {
			return telemetry.New(ctx, cfg)
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return httpingress.NewHandler(enforcer)
		},
		newServer: func(addr string, handler http.Handler) httpServer {
			return defaultNewServer(addr, handler)
		},
		shutdownTimeout:  defaultShutdownTimeout,
		telemetryTimeout: defaultShutdownTimeout,
	}
}

func defaultNewServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: defaultReadHeaderTimeout,
		ReadTimeout:       defaultReadTimeout,
		WriteTimeout:      defaultWriteTimeout,
		IdleTimeout:       defaultIdleTimeout,
	}
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := run(ctx); err != nil {
		log.Printf("gateway failed: %v", err)
		os.Exit(1)
	}
}

func run(ctx context.Context) error {
	return runWithDeps(ctx, defaultDeps())
}

func runWithDeps(ctx context.Context, deps serverDeps) error {
	log.Println("gateway starting")

	cfg, err := deps.loadConfig()
	if err != nil {
		log.Println("gateway startup failed")
		return ErrConfigLoadFailed
	}

	tel, err := deps.newTelemetry(ctx, cfg.Telemetry)
	if err != nil {
		log.Println("gateway startup failed")
		return ErrTelemetryInitFailed
	}

	shutdownTelemetry := func() error {
		timeout := deps.telemetryTimeout
		if timeout <= 0 {
			timeout = defaultShutdownTimeout
		}
		shutdownCtx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		return tel.Shutdown(shutdownCtx)
	}

	rt, err := deps.newRuntime(ctx, cfg.Config)
	if err != nil {
		_ = shutdownTelemetry()
		log.Println("gateway startup failed")
		return ErrRuntimeInitFailed
	}

	handler, err := deps.newHandler(rt.Enforcer())
	if err != nil {
		rt.Close()
		_ = shutdownTelemetry()
		log.Println("gateway startup failed")
		return ErrHandlerInitFailed
	}

	wrappedExecution := tel.WrapExecutionHandler(handler)
	srv := deps.newServer(defaultListenAddress, newRoutes(wrappedExecution, handler, rt.Ready))
	shutdownTimeout := deps.shutdownTimeout
	if shutdownTimeout <= 0 {
		shutdownTimeout = defaultShutdownTimeout
	}

	serverErr := make(chan error, 1)
	go func() {
		serverErr <- srv.ListenAndServe()
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
		shutdownErr := srv.Shutdown(shutdownCtx)
		cancel()

		if shutdownErr != nil {
			_ = srv.Close()
		}

		serveErr := <-serverErr

		// CRITICAL: Close runtime AFTER HTTP server shutdown completes
		rt.Close()

		// CRITICAL: Shut down telemetry AFTER runtime Close completes
		telErr := shutdownTelemetry()

		if shutdownErr != nil {
			log.Println("gateway shutdown failed")
			return ErrShutdownFailed
		}

		if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			log.Println("gateway shutdown failed")
			return ErrShutdownServerError
		}

		if telErr != nil {
			log.Println("gateway shutdown failed")
			return ErrShutdownFailed
		}

		log.Println("gateway stopped")
		return nil

	case serveErr := <-serverErr:
		rt.Close()
		_ = shutdownTelemetry()
		if ctx.Err() != nil && (serveErr == nil || errors.Is(serveErr, http.ErrServerClosed)) {
			log.Println("gateway stopped")
			return nil
		}
		log.Println("gateway startup failed")
		return ErrServerExited
	}
}

func newRoutes(tracedExecutionHandler, untracedExecutionHandler http.Handler, ready func(context.Context) error) http.Handler {
	mux := http.NewServeMux()
	// POST /v1/executions routes to the traced execution handler.
	mux.Handle("POST "+httpingress.ExecutionEndpoint, tracedExecutionHandler)
	// Other methods on /v1/executions route to the untraced ingress handler,
	// preserving ingress's exact method validation, 405/Allow behavior, and sanitized responses.
	mux.Handle(httpingress.ExecutionEndpoint, untracedExecutionHandler)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.Header().Set("Allow", http.MethodGet)
			writeProbe(w, http.StatusMethodNotAllowed, "method_not_allowed")
			return
		}
		writeProbe(w, http.StatusOK, "ok")
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.Header().Set("Allow", http.MethodGet)
			writeProbe(w, http.StatusMethodNotAllowed, "method_not_allowed")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), readinessTimeout)
		defer cancel()
		if err := ready(ctx); err != nil {
			writeProbe(w, http.StatusServiceUnavailable, "not_ready")
			return
		}
		writeProbe(w, http.StatusOK, "ready")
	})
	return mux
}

func writeProbe(w http.ResponseWriter, code int, status string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(`{"status":"` + status + `"}` + "\n"))
}
