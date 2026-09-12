package main

import (
	"context"
	"fmt"
	"io"
	"os"
	"time"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/dbmigrate"
)

const (
	defaultConnectTimeout   = 10 * time.Second
	defaultMigrationTimeout = 30 * time.Second
)

const (
	msgSuccess = "gateway migrations complete"
	msgFailure = "gateway migration failed"
)

func main() {
	code := run(context.Background(), os.LookupEnv, os.Stdout, os.Stderr, defaultConnectTimeout, defaultMigrationTimeout)
	if code != 0 {
		os.Exit(code)
	}
}

func run(
	ctx context.Context,
	lookup dbmigrate.LookupEnv,
	stdout, stderr io.Writer,
	connectTimeout, migrationTimeout time.Duration,
) int {
	cfg, err := dbmigrate.LoadConfigWith(lookup)
	if err != nil {
		fmt.Fprintln(stderr, msgFailure)
		return 1
	}

	m, err := dbmigrate.New(cfg.DatabaseURL)
	if err != nil {
		fmt.Fprintln(stderr, msgFailure)
		return 1
	}
	defer m.Close()

	pingCtx, pingCancel := context.WithTimeout(ctx, connectTimeout)
	defer pingCancel()

	if err := m.Ping(pingCtx); err != nil {
		fmt.Fprintln(stderr, msgFailure)
		return 1
	}

	migCtx, migCancel := context.WithTimeout(ctx, migrationTimeout)
	defer migCancel()

	if err := m.Up(migCtx); err != nil {
		fmt.Fprintln(stderr, msgFailure)
		return 1
	}

	if err := m.Close(); err != nil {
		fmt.Fprintln(stderr, msgFailure)
		return 1
	}

	fmt.Fprintln(stdout, msgSuccess)
	return 0
}
