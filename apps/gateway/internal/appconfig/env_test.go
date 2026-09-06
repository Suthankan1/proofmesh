package appconfig

import (
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
	httpexecutor "github.com/Suthankan1/proofmesh/apps/gateway/internal/toolexecutor/http"
)

func validEnvMap() map[string]string {
	return map[string]string{
		EnvDatabaseURL:            "postgres://user:secret@localhost:5432/proofmesh?sslmode=disable",
		EnvExecutionGrantIssuer:   "https://controlplane.proofmesh.local",
		EnvExecutionGrantAudience: "proofmesh-gateway",
		EnvJWKSURL:                "https://controlplane.proofmesh.local/.well-known/jwks.json",
		EnvToolTargetsJSON: `[
			{
				"tool_name": "payments",
				"operation_name": "execute",
				"url": "https://payments.internal/v1/execute"
			},
			{
				"tool_name": "ledger",
				"operation_name": "record",
				"url": "https://ledger.internal/v1/record"
			}
		]`,
	}
}

func mapLookup(m map[string]string) lookupEnv {
	return func(key string) (string, bool) {
		val, ok := m[key]
		return val, ok
	}
}

func TestLoad_Positive(t *testing.T) {
	env := validEnvMap()
	cfg, err := load(mapLookup(env))
	if err != nil {
		t.Fatalf("expected load to succeed, got: %v", err)
	}

	if cfg.DatabaseURL != env[EnvDatabaseURL] {
		t.Errorf("DatabaseURL mismatch: got %q, want %q", cfg.DatabaseURL, env[EnvDatabaseURL])
	}
	if cfg.Issuer != env[EnvExecutionGrantIssuer] {
		t.Errorf("Issuer mismatch: got %q, want %q", cfg.Issuer, env[EnvExecutionGrantIssuer])
	}
	if cfg.Audience != env[EnvExecutionGrantAudience] {
		t.Errorf("Audience mismatch: got %q, want %q", cfg.Audience, env[EnvExecutionGrantAudience])
	}
	if cfg.JWKSURL != env[EnvJWKSURL] {
		t.Errorf("JWKSURL mismatch: got %q, want %q", cfg.JWKSURL, env[EnvJWKSURL])
	}

	if len(cfg.ToolTargets) != 2 {
		t.Fatalf("expected 2 targets, got %d", len(cfg.ToolTargets))
	}

	expectedTargets := []httpexecutor.Target{
		{
			ToolName:      "payments",
			OperationName: "execute",
			URL:           "https://payments.internal/v1/execute",
		},
		{
			ToolName:      "ledger",
			OperationName: "record",
			URL:           "https://ledger.internal/v1/record",
		},
	}

	for i, want := range expectedTargets {
		got := cfg.ToolTargets[i]
		if got.ToolName != want.ToolName || got.OperationName != want.OperationName || got.URL != want.URL {
			t.Errorf("target[%d] mismatch: got %+v, want %+v", i, got, want)
		}
	}

	// Verify policy fields remain zero-valued so Runtime's safe defaults are applied.
	if cfg.JWKSPolicy != (executiongrant.JWKSResolverPolicy{}) {
		t.Errorf("expected zero-valued JWKSPolicy, got %+v", cfg.JWKSPolicy)
	}
	if cfg.ToolPolicy != (httpexecutor.Policy{}) {
		t.Errorf("expected zero-valued ToolPolicy, got %+v", cfg.ToolPolicy)
	}
}

func TestLoad_MissingAndBlankEnvironment(t *testing.T) {
	requiredKeys := []string{
		EnvDatabaseURL,
		EnvExecutionGrantIssuer,
		EnvExecutionGrantAudience,
		EnvJWKSURL,
		EnvToolTargetsJSON,
	}

	for _, key := range requiredKeys {
		t.Run("absent/"+key, func(t *testing.T) {
			env := validEnvMap()
			delete(env, key)

			_, err := load(mapLookup(env))
			if err == nil {
				t.Fatalf("expected error for missing %s, got nil", key)
			}
			if !errors.Is(err, ErrMissingEnvironment) {
				t.Errorf("expected ErrMissingEnvironment, got: %v", err)
			}
			if !strings.Contains(err.Error(), key) {
				t.Errorf("expected error message to mention %q, got: %v", key, err)
			}
		})

		t.Run("blank/"+key, func(t *testing.T) {
			blanks := []string{"", " ", "\t\n "}
			for _, blank := range blanks {
				env := validEnvMap()
				env[key] = blank

				_, err := load(mapLookup(env))
				if err == nil {
					t.Fatalf("expected error for blank %s, got nil", key)
				}
				if !errors.Is(err, ErrInvalidEnvironment) {
					t.Errorf("expected ErrInvalidEnvironment, got: %v", err)
				}
				if !strings.Contains(err.Error(), key) {
					t.Errorf("expected error message to mention %q, got: %v", key, err)
				}
			}
		})
	}
}

func TestLoad_StrictJSONParsing(t *testing.T) {
	tests := []struct {
		name    string
		rawJSON string
	}{
		{
			name:    "malformed JSON",
			rawJSON: `[{"tool_name":`,
		},
		{
			name:    "object instead of array",
			rawJSON: `{"tool_name":"payments","operation_name":"execute","url":"https://payments.internal"}`,
		},
		{
			name:    "null value",
			rawJSON: `null`,
		},
		{
			name:    "empty array",
			rawJSON: `[]`,
		},
		{
			name:    "whitespace only array",
			rawJSON: `[   ]`,
		},
		{
			name:    "unknown field rejected",
			rawJSON: `[{"tool_name":"payments","operation_name":"execute","url":"https://payments.internal","unexpected":"field"}]`,
		},
		{
			name:    "trailing garbage tokens",
			rawJSON: `[{"tool_name":"payments","operation_name":"execute","url":"https://payments.internal"}] trailing_garbage`,
		},
		{
			name:    "trailing closing brace",
			rawJSON: `[{"tool_name":"payments","operation_name":"execute","url":"https://payments.internal"}]}`,
		},
		{
			name:    "second JSON value",
			rawJSON: `[{"tool_name":"payments","operation_name":"execute","url":"https://payments.internal"}] {"extra": 1}`,
		},
		{
			name:    "primitive integer value",
			rawJSON: `12345`,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			env := validEnvMap()
			env[EnvToolTargetsJSON] = tc.rawJSON

			_, err := load(mapLookup(env))
			if err == nil {
				t.Fatalf("expected error for %s, got nil", tc.name)
			}
			if !errors.Is(err, ErrInvalidEnvironment) {
				t.Errorf("expected ErrInvalidEnvironment, got: %v", err)
			}
			if !strings.Contains(err.Error(), EnvToolTargetsJSON) {
				t.Errorf("expected error to identify %s, got: %v", EnvToolTargetsJSON, err)
			}
		})
	}
}

func TestLoad_TargetShapeValidation(t *testing.T) {
	tests := []struct {
		name    string
		rawJSON string
	}{
		{
			name:    "blank tool_name",
			rawJSON: `[{"tool_name":"   ","operation_name":"execute","url":"https://example.com"}]`,
		},
		{
			name:    "missing tool_name",
			rawJSON: `[{"operation_name":"execute","url":"https://example.com"}]`,
		},
		{
			name:    "blank operation_name",
			rawJSON: `[{"tool_name":"payments","operation_name":"","url":"https://example.com"}]`,
		},
		{
			name:    "missing operation_name",
			rawJSON: `[{"tool_name":"payments","url":"https://example.com"}]`,
		},
		{
			name:    "blank url",
			rawJSON: `[{"tool_name":"payments","operation_name":"execute","url":"\t\n "}]`,
		},
		{
			name:    "missing url",
			rawJSON: `[{"tool_name":"payments","operation_name":"execute"}]`,
		},
		{
			name:    "null element in array",
			rawJSON: `[null]`,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			env := validEnvMap()
			env[EnvToolTargetsJSON] = tc.rawJSON

			_, err := load(mapLookup(env))
			if err == nil {
				t.Fatalf("expected error for %s, got nil", tc.name)
			}
			if !errors.Is(err, ErrInvalidEnvironment) {
				t.Errorf("expected ErrInvalidEnvironment, got: %v", err)
			}
		})
	}
}

func TestLoad_DuplicateTargetKey(t *testing.T) {
	t.Run("exact duplicate rejected", func(t *testing.T) {
		env := validEnvMap()
		env[EnvToolTargetsJSON] = `[
			{
				"tool_name": "payments",
				"operation_name": "execute",
				"url": "https://payments.internal/v1/execute"
			},
			{
				"tool_name": "payments",
				"operation_name": "execute",
				"url": "https://payments-mirror.internal/v1/execute"
			}
		]`

		_, err := load(mapLookup(env))
		if err == nil {
			t.Fatal("expected error on duplicate target mapping, got nil")
		}
		if !errors.Is(err, ErrInvalidEnvironment) {
			t.Errorf("expected ErrInvalidEnvironment, got: %v", err)
		}
	})

	t.Run("case distinct keys accepted by loader", func(t *testing.T) {
		env := validEnvMap()
		env[EnvToolTargetsJSON] = `[
			{
				"tool_name": "payments",
				"operation_name": "execute",
				"url": "https://payments.internal/v1/execute"
			},
			{
				"tool_name": "Payments",
				"operation_name": "execute",
				"url": "https://payments2.internal/v1/execute"
			}
		]`

		cfg, err := load(mapLookup(env))
		if err != nil {
			t.Fatalf("expected case-distinct keys to be accepted by loader, got: %v", err)
		}
		if len(cfg.ToolTargets) != 2 {
			t.Fatalf("expected 2 targets, got %d", len(cfg.ToolTargets))
		}
	})
}

func TestLoad_ExactPreservation(t *testing.T) {
	env := validEnvMap()
	env[EnvExecutionGrantIssuer] = "  https://issuer.internal/custom-path  "
	env[EnvExecutionGrantAudience] = "  audience-with-surrounding-space  "
	env[EnvDatabaseURL] = "postgres://user:pass@host:5432/db?sslmode=verify-full&sslrootcert=/etc/ssl/cert.pem"
	env[EnvJWKSURL] = "https://jwks.internal/keys.json?version=2"
	env[EnvToolTargetsJSON] = `[
		{
			"tool_name": "custom tool",
			"operation_name": "do op",
			"url": "https://custom.internal/api?q=test"
		}
	]`

	cfg, err := load(mapLookup(env))
	if err != nil {
		t.Fatalf("expected exact load to succeed, got: %v", err)
	}

	if cfg.Issuer != env[EnvExecutionGrantIssuer] {
		t.Errorf("Issuer was mutated: got %q, want %q", cfg.Issuer, env[EnvExecutionGrantIssuer])
	}
	if cfg.Audience != env[EnvExecutionGrantAudience] {
		t.Errorf("Audience was mutated: got %q, want %q", cfg.Audience, env[EnvExecutionGrantAudience])
	}
	if cfg.DatabaseURL != env[EnvDatabaseURL] {
		t.Errorf("DatabaseURL was mutated: got %q, want %q", cfg.DatabaseURL, env[EnvDatabaseURL])
	}
	if cfg.JWKSURL != env[EnvJWKSURL] {
		t.Errorf("JWKSURL was mutated: got %q, want %q", cfg.JWKSURL, env[EnvJWKSURL])
	}

	if len(cfg.ToolTargets) != 1 {
		t.Fatalf("expected 1 target, got %d", len(cfg.ToolTargets))
	}
	target := cfg.ToolTargets[0]
	if target.ToolName != "custom tool" {
		t.Errorf("ToolName mutated: got %q", target.ToolName)
	}
	if target.OperationName != "do op" {
		t.Errorf("OperationName mutated: got %q", target.OperationName)
	}
	if target.URL != "https://custom.internal/api?q=test" {
		t.Errorf("URL mutated: got %q", target.URL)
	}
}

func TestLoad_SecretRedaction(t *testing.T) {
	const (
		dbSecretCanary     = "SUPER_SECRET_DB_PASSWORD_CANARY_12345"
		targetSecretCanary = "TARGET_ENDPOINT_SECRET_CANARY_67890"
		issuerSecretCanary = "ISSUER_SECRET_CANARY_ABCDE"
	)

	canaries := []string{dbSecretCanary, targetSecretCanary, issuerSecretCanary}

	assertNoCanaryLeakage := func(t *testing.T, scenario string, err error) {
		t.Helper()
		if err == nil {
			t.Fatalf("[%s] expected error, got nil", scenario)
		}

		formats := []string{
			err.Error(),
			fmt.Sprintf("%v", err),
			fmt.Sprintf("%+v", err),
		}

		for _, formatted := range formats {
			for _, canary := range canaries {
				if strings.Contains(formatted, canary) {
					t.Fatalf("[%s] leaked canary %q in formatted error: %s", scenario, canary, formatted)
				}
			}
		}
	}

	t.Run("malformed json with secret target", func(t *testing.T) {
		env := validEnvMap()
		env[EnvDatabaseURL] = fmt.Sprintf("postgres://user:%s@localhost:5432/db", dbSecretCanary)
		env[EnvExecutionGrantIssuer] = issuerSecretCanary
		env[EnvToolTargetsJSON] = fmt.Sprintf(`[{"tool_name": "payments", "url": "https://%s.com" malformed`, targetSecretCanary)

		_, err := load(mapLookup(env))
		assertNoCanaryLeakage(t, "malformed-json", err)
	})

	t.Run("duplicate targets with secret target", func(t *testing.T) {
		env := validEnvMap()
		env[EnvDatabaseURL] = fmt.Sprintf("postgres://user:%s@localhost:5432/db", dbSecretCanary)
		env[EnvExecutionGrantIssuer] = issuerSecretCanary
		env[EnvToolTargetsJSON] = fmt.Sprintf(`[
			{"tool_name": "payments", "operation_name": "execute", "url": "https://%s.com/1"},
			{"tool_name": "payments", "operation_name": "execute", "url": "https://%s.com/2"}
		]`, targetSecretCanary, targetSecretCanary)

		_, err := load(mapLookup(env))
		assertNoCanaryLeakage(t, "duplicate-target", err)
	})

	t.Run("missing audience with sensitive secrets configured", func(t *testing.T) {
		env := validEnvMap()
		env[EnvDatabaseURL] = fmt.Sprintf("postgres://user:%s@localhost:5432/db", dbSecretCanary)
		env[EnvExecutionGrantIssuer] = issuerSecretCanary
		env[EnvToolTargetsJSON] = fmt.Sprintf(`[{"tool_name": "payments", "operation_name": "execute", "url": "https://%s.com"}]`, targetSecretCanary)
		delete(env, EnvExecutionGrantAudience)

		_, err := load(mapLookup(env))
		assertNoCanaryLeakage(t, "missing-audience", err)
	})

	t.Run("blank target URL with secrets", func(t *testing.T) {
		env := validEnvMap()
		env[EnvDatabaseURL] = fmt.Sprintf("postgres://user:%s@localhost:5432/db", dbSecretCanary)
		env[EnvExecutionGrantIssuer] = issuerSecretCanary
		env[EnvToolTargetsJSON] = `[{"tool_name": "payments", "operation_name": "execute", "url": "   "}]`

		_, err := load(mapLookup(env))
		assertNoCanaryLeakage(t, "blank-url", err)
	})
}

func TestLoad_PublicAPIWithOSLookup(t *testing.T) {
	env := validEnvMap()
	for k, v := range env {
		t.Setenv(k, v)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatalf("expected public Load() to succeed, got: %v", err)
	}

	if cfg.DatabaseURL != env[EnvDatabaseURL] {
		t.Errorf("DatabaseURL mismatch: got %q, want %q", cfg.DatabaseURL, env[EnvDatabaseURL])
	}
	if len(cfg.ToolTargets) != 2 {
		t.Errorf("expected 2 targets, got %d", len(cfg.ToolTargets))
	}
}
