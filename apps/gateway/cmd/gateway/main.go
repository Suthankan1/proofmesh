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
)

const (
	defaultListenAddress     = ":8080"
	defaultReadHeaderTimeout = 5 * time.Second
	defaultReadTimeout       = 15 * time.Second
	defaultWriteTimeout      = 30 * time.Second
	defaultIdleTimeout       = 60 * time.Second
	defaultShutdownTimeout   = 10 * time.Second
)

// Sanitized sentinel errors returned by gateway application bootstrap and lifecycle.
// Infrastructure details, connection strings, credentials, and internal errors
// are never logged or leaked across this boundary.
var (
	ErrConfigLoadFailed    = errors.New("gateway: failed to load configuration")
	ErrRuntimeInitFailed   = errors.New("gateway: failed to initialize runtime")
	ErrHandlerInitFailed   = errors.New("gateway: failed to initialize handler")
	ErrServerExited        = errors.New("gateway: server exited unexpectedly")
	ErrShutdownFailed      = errors.New("gateway: graceful shutdown failed")
	ErrShutdownServerError = errors.New("gateway: server error during shutdown")
)

type gatewayRuntime interface {
	Enforcer() *pep.Enforcer
	Close()
}

type httpServer interface {
	ListenAndServe() error
	Shutdown(ctx context.Context) error
	Close() error
}

type serverDeps struct {
	loadConfig      func() (runtime.Config, error)
	newRuntime      func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error)
	newHandler      func(enforcer *pep.Enforcer) (http.Handler, error)
	newServer       func(addr string, handler http.Handler) httpServer
	shutdownTimeout time.Duration
}

func defaultDeps() serverDeps {
	return serverDeps{
		loadConfig: appconfig.Load,
		newRuntime: func(ctx context.Context, cfg runtime.Config) (gatewayRuntime, error) {
			return runtime.New(ctx, cfg)
		},
		newHandler: func(enforcer *pep.Enforcer) (http.Handler, error) {
			return httpingress.NewHandler(enforcer)
		},
		newServer: func(addr string, handler http.Handler) httpServer {
			return defaultNewServer(addr, handler)
		},
		shutdownTimeout: defaultShutdownTimeout,
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

	rt, err := deps.newRuntime(ctx, cfg)
	if err != nil {
		log.Println("gateway startup failed")
		return ErrRuntimeInitFailed
	}

	handler, err := deps.newHandler(rt.Enforcer())
	if err != nil {
		rt.Close()
		log.Println("gateway startup failed")
		return ErrHandlerInitFailed
	}

	srv := deps.newServer(defaultListenAddress, handler)
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

		if shutdownErr != nil {
			log.Println("gateway shutdown failed")
			return ErrShutdownFailed
		}

		if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			log.Println("gateway shutdown failed")
			return ErrShutdownServerError
		}

		log.Println("gateway stopped")
		return nil

	case serveErr := <-serverErr:
		rt.Close()
		if ctx.Err() != nil && (serveErr == nil || errors.Is(serveErr, http.ErrServerClosed)) {
			log.Println("gateway stopped")
			return nil
		}
		log.Println("gateway startup failed")
		return ErrServerExited
	}
}
