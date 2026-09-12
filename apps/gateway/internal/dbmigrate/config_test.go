package dbmigrate_test

import (
	"errors"
	"strings"
	"testing"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/dbmigrate"
)

func TestLoadConfig_MissingEnv(t *testing.T) {
	lookup := func(key string) (string, bool) {
		return "", false
	}

	_, err := dbmigrate.LoadConfigWith(lookup)
	if err == nil {
		t.Fatal("expected error for missing environment, got nil")
	}
	if !errors.Is(err, dbmigrate.ErrMissingDatabaseURL) {
		t.Fatalf("expected ErrMissingDatabaseURL, got %v", err)
	}
}

func TestLoadConfig_BlankEnv(t *testing.T) {
	for _, raw := range []string{"", "   ", "\t\n"} {
		lookup := func(key string) (string, bool) {
			if key == dbmigrate.EnvDatabaseURL {
				return raw, true
			}
			return "", false
		}

		_, err := dbmigrate.LoadConfigWith(lookup)
		if err == nil {
			t.Fatalf("expected error for blank value %q, got nil", raw)
		}
		if !errors.Is(err, dbmigrate.ErrBlankDatabaseURL) {
			t.Fatalf("expected ErrBlankDatabaseURL for %q, got %v", raw, err)
		}
	}
}

func TestLoadConfig_ValidEnv(t *testing.T) {
	const expectedURL = "postgres://user:pass@localhost:5432/proofmesh?sslmode=disable"
	lookup := func(key string) (string, bool) {
		if key == dbmigrate.EnvDatabaseURL {
			return expectedURL, true
		}
		return "", false
	}

	cfg, err := dbmigrate.LoadConfigWith(lookup)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.DatabaseURL != expectedURL {
		t.Fatalf("expected DatabaseURL %q, got %q", expectedURL, cfg.DatabaseURL)
	}
}

func TestLoadConfig_Sanitization_NoCanaryLeak(t *testing.T) {
	const canarySecret = "SUPER_SECRET_CANARY_PASSWORD_123"
	lookup := func(key string) (string, bool) {
		if key == dbmigrate.EnvDatabaseURL {
			return "   ", true
		}
		return "", false
	}

	_, err := dbmigrate.LoadConfigWith(lookup)
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	errStr := err.Error()
	if strings.Contains(errStr, canarySecret) {
		t.Fatalf("canary secret leaked in error: %s", errStr)
	}
	if strings.Contains(errStr, "postgres://") {
		t.Fatalf("connection prefix leaked in error: %s", errStr)
	}
}
