package main

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/dbmigrate"
)

var testConnStr string

func TestMain(m *testing.M) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	pgContainer, err := tcpostgres.Run(ctx,
		"postgres:18.6-alpine",
		tcpostgres.WithDatabase("proofmesh_cmd_migrate_test"),
		tcpostgres.WithUsername("postgres"),
		tcpostgres.WithPassword("postgres"),
		tcpostgres.BasicWaitStrategies(),
	)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to start testcontainer: %v\n", err)
		os.Exit(1)
	}

	connStr, err := pgContainer.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to get connection string: %v\n", err)
		_ = pgContainer.Terminate(context.Background())
		os.Exit(1)
	}
	testConnStr = connStr

	code := m.Run()

	_ = pgContainer.Terminate(context.Background())
	os.Exit(code)
}

func TestRun_MissingDBURL(t *testing.T) {
	var stdout, stderr bytes.Buffer
	lookup := func(key string) (string, bool) {
		return "", false
	}

	code := run(context.Background(), lookup, &stdout, &stderr, 5*time.Second, 5*time.Second)
	if code != 1 {
		t.Fatalf("expected exit code 1, got %d", code)
	}
	if stdout.Len() != 0 {
		t.Fatalf("expected empty stdout, got %q", stdout.String())
	}
	if strings.TrimSpace(stderr.String()) != msgFailure {
		t.Fatalf("expected stderr %q, got %q", msgFailure, stderr.String())
	}
}

func TestRun_BlankDBURL(t *testing.T) {
	for _, raw := range []string{"", "   ", "\t\n"} {
		var stdout, stderr bytes.Buffer
		lookup := func(key string) (string, bool) {
			if key == dbmigrate.EnvDatabaseURL {
				return raw, true
			}
			return "", false
		}

		code := run(context.Background(), lookup, &stdout, &stderr, 5*time.Second, 5*time.Second)
		if code != 1 {
			t.Fatalf("expected exit code 1 for blank value, got %d", code)
		}
		if stdout.Len() != 0 {
			t.Fatalf("expected empty stdout, got %q", stdout.String())
		}
		if strings.TrimSpace(stderr.String()) != msgFailure {
			t.Fatalf("expected stderr %q, got %q", msgFailure, stderr.String())
		}
	}
}

func TestRun_ConnectionFailure_Sanitized(t *testing.T) {
	const canarySecret = "CANARY_PW_999888"
	const canaryHost = "127.0.0.1:59998"
	canaryURL := fmt.Sprintf("postgres://user:%s@%s/canary_db?sslmode=disable", canarySecret, canaryHost)

	var stdout, stderr bytes.Buffer
	lookup := func(key string) (string, bool) {
		if key == dbmigrate.EnvDatabaseURL {
			return canaryURL, true
		}
		return "", false
	}

	code := run(context.Background(), lookup, &stdout, &stderr, 200*time.Millisecond, 200*time.Millisecond)
	if code != 1 {
		t.Fatalf("expected exit code 1 on connection failure, got %d", code)
	}
	if stdout.Len() != 0 {
		t.Fatalf("expected empty stdout, got %q", stdout.String())
	}
	errOutput := stderr.String()
	if strings.TrimSpace(errOutput) != msgFailure {
		t.Fatalf("expected stderr %q, got %q", msgFailure, errOutput)
	}
	if strings.Contains(errOutput, canarySecret) {
		t.Fatalf("canary secret leaked in stderr: %s", errOutput)
	}
	if strings.Contains(errOutput, canaryHost) {
		t.Fatalf("canary host leaked in stderr: %s", errOutput)
	}
}

func TestRun_BoundedContext_Timeout(t *testing.T) {
	if testConnStr == "" {
		t.Skip("testcontainer database not available")
	}

	var stdout, stderr bytes.Buffer
	lookup := func(key string) (string, bool) {
		if key == dbmigrate.EnvDatabaseURL {
			return testConnStr, true
		}
		return "", false
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // pre-cancelled context

	code := run(ctx, lookup, &stdout, &stderr, 5*time.Second, 5*time.Second)
	if code != 1 {
		t.Fatalf("expected exit code 1 on cancelled context, got %d", code)
	}
	if strings.TrimSpace(stderr.String()) != msgFailure {
		t.Fatalf("expected stderr %q, got %q", msgFailure, stderr.String())
	}
}

func TestRun_SuccessfulMigration_And_RerunNoOp(t *testing.T) {
	if testConnStr == "" {
		t.Skip("testcontainer database not available")
	}

	lookup := func(key string) (string, bool) {
		if key == dbmigrate.EnvDatabaseURL {
			return testConnStr, true
		}
		return "", false
	}

	// First run: applies migration
	{
		var stdout, stderr bytes.Buffer
		code := run(context.Background(), lookup, &stdout, &stderr, 10*time.Second, 30*time.Second)
		if code != 0 {
			t.Fatalf("expected exit code 0 on initial run, got %d (stderr: %s)", code, stderr.String())
		}
		if stderr.Len() != 0 {
			t.Fatalf("expected empty stderr, got %q", stderr.String())
		}
		if strings.TrimSpace(stdout.String()) != msgSuccess {
			t.Fatalf("expected stdout %q, got %q", msgSuccess, stdout.String())
		}
	}

	// Second run: idempotent no-op
	{
		var stdout, stderr bytes.Buffer
		code := run(context.Background(), lookup, &stdout, &stderr, 10*time.Second, 30*time.Second)
		if code != 0 {
			t.Fatalf("expected exit code 0 on rerun, got %d (stderr: %s)", code, stderr.String())
		}
		if stderr.Len() != 0 {
			t.Fatalf("expected empty stderr, got %q", stderr.String())
		}
		if strings.TrimSpace(stdout.String()) != msgSuccess {
			t.Fatalf("expected stdout %q, got %q", msgSuccess, stdout.String())
		}
	}
}
