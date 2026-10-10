// Command crossservice is a local integration-test host, not a production entrypoint.
// It uses the production runtime and HTTP ingress with an ephemeral loopback listener.
package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/appconfig"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/dbmigrate"
	httpingress "github.com/Suthankan1/proofmesh/apps/gateway/internal/ingress/http"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/runtime"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "crossservice host failed")
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	cfg, err := appconfig.Load()
	if err != nil {
		return err
	}
	startup, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	// Explicit one-shot migration belongs to this disposable test database only.
	if err := dbmigrate.Run(startup, cfg.DatabaseURL); err != nil {
		return err
	}
	rt, err := runtime.New(startup, cfg.Config)
	if err != nil {
		return err
	}
	defer rt.Close()
	handler, err := httpingress.NewHandler(rt.Enforcer())
	if err != nil {
		return err
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout: 10 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 30 * time.Second}
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	fmt.Println("http://" + listener.Addr().String())
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdown); err != nil {
			_ = server.Close()
			return err
		}
		<-done
		return nil
	}
}
