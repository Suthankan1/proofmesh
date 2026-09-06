package runtime

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/lestrrat-go/jwx/v3/jwa"
	"github.com/lestrrat-go/jwx/v3/jws"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/canonicalize"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executionattempt"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
	httpexecutor "github.com/Suthankan1/proofmesh/apps/gateway/internal/toolexecutor/http"
)

var testConnStr string

func TestMain(m *testing.M) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	pgContainer, err := tcpostgres.Run(ctx,
		"postgres:18.6-alpine",
		tcpostgres.WithDatabase("proofmesh_test"),
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

	// Apply migration 0001
	migrationSQL, err := readMigrationSQL()
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to read migration SQL: %v\n", err)
		_ = pgContainer.Terminate(context.Background())
		os.Exit(1)
	}

	setupPool, err := pgxpool.New(ctx, testConnStr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to connect to test postgres: %v\n", err)
		_ = pgContainer.Terminate(context.Background())
		os.Exit(1)
	}

	if _, err := setupPool.Exec(ctx, migrationSQL); err != nil {
		fmt.Fprintf(os.Stderr, "failed to apply migration: %v\n", err)
		setupPool.Close()
		_ = pgContainer.Terminate(context.Background())
		os.Exit(1)
	}
	setupPool.Close()

	code := m.Run()

	termCtx, termCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer termCancel()
	_ = pgContainer.Terminate(termCtx)

	os.Exit(code)
}

func readMigrationSQL() (string, error) {
	candidates := []string{
		"../../migrations/0001_create_execution_grant_claims.sql",
		"migrations/0001_create_execution_grant_claims.sql",
		"../migrations/0001_create_execution_grant_claims.sql",
		"../../../migrations/0001_create_execution_grant_claims.sql",
	}

	for _, p := range candidates {
		data, err := os.ReadFile(p)
		if err == nil {
			return string(data), nil
		}
	}
	return "", errors.New("could not find migration file")
}

// 1. Config Validation Tests
func TestConfigValidation(t *testing.T) {
	validTarget := httpexecutor.Target{
		ToolName:      "payments",
		OperationName: "refund",
		URL:           "http://localhost:8080/refund",
	}

	validCfg := func() Config {
		return Config{
			DatabaseURL: testConnStr,
			Issuer:      "https://proofmesh.io/issuer",
			Audience:    "https://gateway.proofmesh.io",
			JWKSURL:     "http://localhost:8080/.well-known/jwks.json",
			ToolTargets: []httpexecutor.Target{validTarget},
		}
	}

	tests := []struct {
		name      string
		modifyCfg func(*Config)
	}{
		{
			name: "blank database URL",
			modifyCfg: func(c *Config) {
				c.DatabaseURL = "   "
			},
		},
		{
			name: "blank issuer",
			modifyCfg: func(c *Config) {
				c.Issuer = ""
			},
		},
		{
			name: "blank audience",
			modifyCfg: func(c *Config) {
				c.Audience = " \t "
			},
		},
		{
			name: "blank JWKS URL",
			modifyCfg: func(c *Config) {
				c.JWKSURL = ""
			},
		},
		{
			name: "nil tool targets",
			modifyCfg: func(c *Config) {
				c.ToolTargets = nil
			},
		},
		{
			name: "empty tool targets",
			modifyCfg: func(c *Config) {
				c.ToolTargets = []httpexecutor.Target{}
			},
		},
		{
			name: "blank target tool name",
			modifyCfg: func(c *Config) {
				c.ToolTargets = []httpexecutor.Target{
					{
						ToolName:      "",
						OperationName: "refund",
						URL:           "http://localhost:8080/refund",
					},
				}
			},
		},
		{
			name: "blank target operation name",
			modifyCfg: func(c *Config) {
				c.ToolTargets = []httpexecutor.Target{
					{
						ToolName:      "payments",
						OperationName: " ",
						URL:           "http://localhost:8080/refund",
					},
				}
			},
		},
		{
			name: "negative tool policy request timeout",
			modifyCfg: func(c *Config) {
				c.ToolPolicy.RequestTimeout = -1 * time.Second
			},
		},
		{
			name: "negative tool policy max response bytes",
			modifyCfg: func(c *Config) {
				c.ToolPolicy.MaxResponseBytes = -1
			},
		},
		{
			name: "invalid JWKS policy negative default TTL",
			modifyCfg: func(c *Config) {
				c.JWKSPolicy = executiongrant.JWKSResolverPolicy{
					DefaultTTL:       -1 * time.Second,
					MaxTTL:           10 * time.Minute,
					RequestTimeout:   5 * time.Second,
					MaxResponseBytes: 1024,
				}
			},
		},
		{
			name: "invalid JWKS policy default TTL exceeds max TTL",
			modifyCfg: func(c *Config) {
				c.JWKSPolicy = executiongrant.JWKSResolverPolicy{
					DefaultTTL:       20 * time.Minute,
					MaxTTL:           10 * time.Minute,
					RequestTimeout:   5 * time.Second,
					MaxResponseBytes: 1024,
				}
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := validCfg()
			tc.modifyCfg(&cfg)

			rt, err := New(context.Background(), cfg)
			if rt != nil {
				t.Fatalf("expected nil Runtime on invalid config, got %v", rt)
			}
			if !errors.Is(err, ErrInvalidConfig) {
				t.Fatalf("expected ErrInvalidConfig, got %v", err)
			}
		})
	}
}

// 2. Database Startup / Unavailable Failure Test
func TestDatabaseUnavailable(t *testing.T) {
	cfg := Config{
		DatabaseURL: "postgres://baduser:secretpassword@127.0.0.1:1/nonexistent?connect_timeout=1",
		Issuer:      "https://proofmesh.io/issuer",
		Audience:    "https://gateway.proofmesh.io",
		JWKSURL:     "http://localhost:8080/.well-known/jwks.json",
		ToolTargets: []httpexecutor.Target{
			{
				ToolName:      "payments",
				OperationName: "refund",
				URL:           "http://localhost:8080/refund",
			},
		},
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	rt, err := New(ctx, cfg)
	if rt != nil {
		t.Fatalf("expected nil runtime on unavailable database, got %v", rt)
	}
	if !errors.Is(err, ErrDatabaseUnavailable) {
		t.Fatalf("expected ErrDatabaseUnavailable, got %v", err)
	}

	// Verify error message does not leak connection string, password, or host
	errStr := err.Error()
	if strings.Contains(errStr, "baduser") || strings.Contains(errStr, "secretpassword") || strings.Contains(errStr, "127.0.0.1") {
		t.Fatalf("error leaked credentials or network details: %s", errStr)
	}
}

// 3. Pool Cleanup on Later Constructor Failure
func TestCleanupOnLaterFailure(t *testing.T) {
	t.Run("pool closed on executor failure", func(t *testing.T) {
		var capturedPool *pgxpool.Pool
		cfg := Config{
			DatabaseURL: testConnStr,
			Issuer:      "https://proofmesh.io/issuer",
			Audience:    "https://gateway.proofmesh.io",
			JWKSURL:     "http://localhost:8080/.well-known/jwks.json",
			ToolTargets: []httpexecutor.Target{
				{
					ToolName:      "payments",
					OperationName: "refund",
					URL:           "://not-a-valid-url", // Fails during httpexecutor.NewExecutor
				},
			},
		}

		deps := runtimeDeps{
			poolFactory: func(ctx context.Context, connStr string) (*pgxpool.Pool, error) {
				p, err := pgxpool.New(ctx, connStr)
				capturedPool = p
				return p, err
			},
		}

		rt, err := newRuntime(context.Background(), cfg, deps)
		if rt != nil {
			t.Fatalf("expected nil runtime, got %v", rt)
		}
		if !errors.Is(err, ErrExecutorInitialization) {
			t.Fatalf("expected ErrExecutorInitialization, got %v", err)
		}

		assertPoolClosed(t, capturedPool)
	})

	t.Run("pool closed on authority failure", func(t *testing.T) {
		var capturedPool *pgxpool.Pool
		cfg := Config{
			DatabaseURL: testConnStr,
			Issuer:      "https://proofmesh.io/issuer",
			Audience:    "https://gateway.proofmesh.io",
			JWKSURL:     "http://localhost:8080/.well-known/jwks.json",
			ToolTargets: []httpexecutor.Target{
				{
					ToolName:      "payments",
					OperationName: "refund",
					URL:           "http://localhost:8080/refund",
				},
			},
		}

		deps := runtimeDeps{
			poolFactory: func(ctx context.Context, connStr string) (*pgxpool.Pool, error) {
				p, err := pgxpool.New(ctx, connStr)
				capturedPool = p
				return p, err
			},
			authorityFactory: func(p *pgxpool.Pool) (pep.ExecutionAuthority, error) {
				return nil, errors.New("simulated authority failure")
			},
		}

		rt, err := newRuntime(context.Background(), cfg, deps)
		if rt != nil {
			t.Fatalf("expected nil runtime, got %v", rt)
		}
		if !errors.Is(err, ErrAuthorityInitialization) {
			t.Fatalf("expected ErrAuthorityInitialization, got %v", err)
		}

		assertPoolClosed(t, capturedPool)
	})

	t.Run("pool closed on resolver failure", func(t *testing.T) {
		var capturedPool *pgxpool.Pool
		cfg := Config{
			DatabaseURL: testConnStr,
			Issuer:      "https://proofmesh.io/issuer",
			Audience:    "https://gateway.proofmesh.io",
			JWKSURL:     "://not-a-valid-jwks-url", // Fails in executiongrant.NewJWKSResolver
			ToolTargets: []httpexecutor.Target{
				{
					ToolName:      "payments",
					OperationName: "refund",
					URL:           "http://localhost:8080/refund",
				},
			},
		}

		deps := runtimeDeps{
			poolFactory: func(ctx context.Context, connStr string) (*pgxpool.Pool, error) {
				p, err := pgxpool.New(ctx, connStr)
				capturedPool = p
				return p, err
			},
		}

		rt, err := newRuntime(context.Background(), cfg, deps)
		if rt != nil {
			t.Fatalf("expected nil runtime, got %v", rt)
		}
		if !errors.Is(err, ErrVerifierInitialization) {
			t.Fatalf("expected ErrVerifierInitialization, got %v", err)
		}

		assertPoolClosed(t, capturedPool)
	})

	t.Run("pool closed on verifier failure", func(t *testing.T) {
		var capturedPool *pgxpool.Pool
		cfg := Config{
			DatabaseURL: testConnStr,
			Issuer:      "https://proofmesh.io/issuer",
			Audience:    "https://gateway.proofmesh.io",
			JWKSURL:     "http://localhost:8080/.well-known/jwks.json",
			ToolTargets: []httpexecutor.Target{
				{
					ToolName:      "payments",
					OperationName: "refund",
					URL:           "http://localhost:8080/refund",
				},
			},
		}

		deps := runtimeDeps{
			poolFactory: func(ctx context.Context, connStr string) (*pgxpool.Pool, error) {
				p, err := pgxpool.New(ctx, connStr)
				capturedPool = p
				return p, err
			},
			verifierFactory: func(iss, aud string, clk executiongrant.Clock, res executiongrant.PublicKeyResolver) (pep.GrantVerifier, error) {
				return nil, errors.New("simulated verifier failure")
			},
		}

		rt, err := newRuntime(context.Background(), cfg, deps)
		if rt != nil {
			t.Fatalf("expected nil runtime, got %v", rt)
		}
		if !errors.Is(err, ErrVerifierInitialization) {
			t.Fatalf("expected ErrVerifierInitialization, got %v", err)
		}

		assertPoolClosed(t, capturedPool)
	})

	t.Run("pool closed on enforcer failure", func(t *testing.T) {
		var capturedPool *pgxpool.Pool
		cfg := Config{
			DatabaseURL: testConnStr,
			Issuer:      "https://proofmesh.io/issuer",
			Audience:    "https://gateway.proofmesh.io",
			JWKSURL:     "http://localhost:8080/.well-known/jwks.json",
			ToolTargets: []httpexecutor.Target{
				{
					ToolName:      "payments",
					OperationName: "refund",
					URL:           "http://localhost:8080/refund",
				},
			},
		}

		deps := runtimeDeps{
			poolFactory: func(ctx context.Context, connStr string) (*pgxpool.Pool, error) {
				p, err := pgxpool.New(ctx, connStr)
				capturedPool = p
				return p, err
			},
			enforcerFactory: func(v pep.GrantVerifier, a pep.ExecutionAuthority, e pep.ToolExecutor, clk executiongrant.Clock) (*pep.Enforcer, error) {
				return nil, errors.New("simulated enforcer failure")
			},
		}

		rt, err := newRuntime(context.Background(), cfg, deps)
		if rt != nil {
			t.Fatalf("expected nil runtime, got %v", rt)
		}
		if !errors.Is(err, ErrEnforcerInitialization) {
			t.Fatalf("expected ErrEnforcerInitialization, got %v", err)
		}

		assertPoolClosed(t, capturedPool)
	})
}

// 4. Lifecycle and Nil-Safety Tests
func TestRuntimeLifecycleAndNilSafety(t *testing.T) {
	// Nil receiver safety
	var nilRt *Runtime
	if nilRt.Enforcer() != nil {
		t.Fatalf("expected nil enforcer on nil runtime")
	}
	// Calling Close on nil receiver must not panic
	nilRt.Close()

	// Idempotent Close on non-nil runtime with pool and owned transport
	pool, err := pgxpool.New(context.Background(), testConnStr)
	if err != nil {
		t.Fatalf("failed to create pool: %v", err)
	}

	rt := &Runtime{
		pool:      pool,
		transport: cloneDefaultTransport(),
	}

	rt.Close()
	rt.Close() // Second call must be idempotent and not panic
	assertPoolClosed(t, pool)
}

func TestRuntime_OwnedTransportCleanup(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	idleClosed := make(chan struct{}, 10)
	connIdle := make(chan struct{}, 10)

	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	}))
	server.Config.ConnState = func(conn net.Conn, state http.ConnState) {
		switch state {
		case http.StateIdle:
			select {
			case connIdle <- struct{}{}:
			default:
			}
		case http.StateClosed:
			select {
			case idleClosed <- struct{}{}:
			default:
			}
		}
	}
	server.Start()
	defer server.Close()

	cfg := Config{
		DatabaseURL: testConnStr,
		Issuer:      "https://proofmesh.io/issuer",
		Audience:    "https://gateway.proofmesh.io",
		JWKSURL:     server.URL,
		ToolTargets: []httpexecutor.Target{
			{
				ToolName:      "payments",
				OperationName: "refund",
				URL:           server.URL,
			},
		},
	}

	// 1. Runtime constructed via New owns the cloned transport
	rt, err := New(ctx, cfg)
	if err != nil {
		t.Fatalf("failed to initialize runtime: %v", err)
	}
	if rt.transport == nil {
		t.Fatalf("expected runtime to own transport when constructed with New")
	}

	// Make an HTTP request through the runtime's owned transport to establish an idle connection
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL, nil)
	if err != nil {
		t.Fatalf("failed to create request: %v", err)
	}
	resp, err := rt.transport.RoundTrip(req)
	if err != nil {
		t.Fatalf("failed to execute request: %v", err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()

	// Wait for connection to become idle in transport pool
	select {
	case <-connIdle:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for connection to become idle")
	}

	// Calling Close() on Runtime must close idle connections and trigger StateClosed on server
	rt.Close()

	select {
	case <-idleClosed:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for idle connection to be closed by Runtime.Close()")
	}

	// Verify idempotency
	rt.Close()

	// 2. Caller-injected transport is NOT owned by Runtime
	injectedTransport := cloneDefaultTransport()
	rtCustom, err := newRuntime(ctx, cfg, runtimeDeps{
		transport: injectedTransport,
	})
	if err != nil {
		t.Fatalf("failed to initialize runtime with injected transport: %v", err)
	}
	if rtCustom.transport != nil {
		t.Fatalf("expected rtCustom.transport to be nil when caller injects transport")
	}
	rtCustom.Close()
}

// 5. API Surface Test (No bypasses)
func TestRuntimeAPISurface(t *testing.T) {
	rtType := reflect.TypeOf((*Runtime)(nil))
	methodCount := rtType.NumMethod()

	allowedMethods := map[string]bool{
		"Close":    true,
		"Enforcer": true,
	}

	for i := 0; i < methodCount; i++ {
		method := rtType.Method(i)
		if !allowedMethods[method.Name] {
			t.Fatalf("unauthorized public method exposed on *Runtime: %s", method.Name)
		}
	}

	forbiddenGetters := []string{
		"Executor",
		"ToolExecutor",
		"Authority",
		"ExecutionAuthority",
		"Verifier",
		"GrantVerifier",
		"Pool",
		"Database",
		"DB",
	}

	for _, name := range forbiddenGetters {
		if _, found := rtType.MethodByName(name); found {
			t.Fatalf("critical invariant violation: *Runtime exposes forbidden getter %s", name)
		}
	}
}

// 6. Successful Composition and Full Execution / Replay Defense Test
func TestComposition_FullExecutionAndReplayDefense(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// 1. Generate P-256 key pair
	privKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate P-256 key: %v", err)
	}
	keyID := "key-prod-01"

	// 2. Start local JWKS server
	jwksBytes := makeJWKSBytes(t, privKey.PublicKey, keyID)
	jwksServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(jwksBytes)
	}))
	defer jwksServer.Close()

	// 3. Start local downstream tool server
	var downstreamCalls int64
	toolServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&downstreamCalls, 1)
		body, _ := io.ReadAll(r.Body)
		defer r.Body.Close()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(fmt.Sprintf(`{"received_bytes":%d,"status":"ok"}`, len(body))))
	}))
	defer toolServer.Close()

	issuer := "https://controlplane.proofmesh.io"
	audience := "https://gateway.proofmesh.io"

	cfg := Config{
		DatabaseURL: testConnStr,
		Issuer:      issuer,
		Audience:    audience,
		JWKSURL:     jwksServer.URL,
		ToolTargets: []httpexecutor.Target{
			{
				ToolName:      "payments",
				OperationName: "refund",
				URL:           toolServer.URL,
			},
		},
	}

	// 4. Construct Runtime
	rt, err := New(ctx, cfg)
	if err != nil {
		t.Fatalf("failed to initialize runtime: %v", err)
	}
	defer rt.Close()

	if rt.Enforcer() == nil {
		t.Fatalf("runtime.Enforcer() is nil")
	}

	// 5. Construct matching grant claims and canonical attempt payload
	orgID := uuid.New()
	agentID := uuid.New()
	actionID := uuid.New()
	decisionID := uuid.New()
	grantID := uuid.New()

	rawPayload := []byte(`{"amount":100,"currency":"USD","reason":"customer_request"}`)
	canonicalBytes, payloadHash, err := canonicalize.CanonicalizeAndHash(rawPayload)
	if err != nil {
		t.Fatalf("canonicalization failed: %v", err)
	}

	now := time.Now().UTC()
	claims := map[string]any{
		"iss":            issuer,
		"aud":            audience,
		"jti":            grantID.String(),
		"iat":            now.Unix(),
		"exp":            now.Add(30 * time.Second).Unix(),
		"org_id":         orgID.String(),
		"agent_id":       agentID.String(),
		"action_id":      actionID.String(),
		"decision_id":    decisionID.String(),
		"tool_name":      "payments",
		"operation_name": "refund",
		"payload_hash":   payloadHash,
	}

	compactToken := buildSignedToken(t, privKey, keyID, claims)

	attempt := executionattempt.ExecutionAttempt{
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decisionID,
		ToolName:             "payments",
		OperationName:        "refund",
		Payload:              canonicalBytes,
	}

	// 6. First execution attempt: MUST SUCCEED and hit downstream tool server
	result, err := rt.Enforcer().Execute(ctx, compactToken, attempt)
	if err != nil {
		t.Fatalf("expected first execution to succeed, got %v", err)
	}
	if len(result.Payload) == 0 {
		t.Fatalf("expected non-empty result payload")
	}
	if atomic.LoadInt64(&downstreamCalls) != 1 {
		t.Fatalf("expected exactly 1 downstream call, got %d", atomic.LoadInt64(&downstreamCalls))
	}

	// 7. Replay attempt with exact same token: MUST BE DENIED and NEVER hit downstream server
	replayResult, replayErr := rt.Enforcer().Execute(ctx, compactToken, attempt)
	if !errors.Is(replayErr, pep.ErrExecutionReplay) {
		t.Fatalf("expected pep.ErrExecutionReplay on replay, got result=%v err=%v", replayResult, replayErr)
	}
	if atomic.LoadInt64(&downstreamCalls) != 1 {
		t.Fatalf("replay hit downstream tool server! calls=%d", atomic.LoadInt64(&downstreamCalls))
	}
}

// --- Test Helpers ---

func assertPoolClosed(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	if pool == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	conn, err := pool.Acquire(ctx)
	if err == nil {
		conn.Release()
		t.Fatalf("expected pool to be closed, but Acquire succeeded")
	}
}

func buildSignedToken(t *testing.T, privKey *ecdsa.PrivateKey, kid string, claims map[string]any) string {
	t.Helper()
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatalf("failed to marshal claims: %v", err)
	}

	headers := jws.NewHeaders()
	if err := headers.Set("typ", "proofmesh-execution-grant+jwt"); err != nil {
		t.Fatalf("failed to set typ header: %v", err)
	}
	if err := headers.Set("kid", kid); err != nil {
		t.Fatalf("failed to set kid header: %v", err)
	}

	signed, err := jws.Sign(payload, jws.WithKey(jwa.ES256(), privKey, jws.WithProtectedHeaders(headers)))
	if err != nil {
		t.Fatalf("failed to sign token: %v", err)
	}
	return string(signed)
}

func makeJWKSBytes(t *testing.T, pubKey ecdsa.PublicKey, kid string) []byte {
	t.Helper()
	xBytes := pubKey.X.Bytes()
	yBytes := pubKey.Y.Bytes()
	if len(xBytes) < 32 {
		padded := make([]byte, 32)
		copy(padded[32-len(xBytes):], xBytes)
		xBytes = padded
	}
	if len(yBytes) < 32 {
		padded := make([]byte, 32)
		copy(padded[32-len(yBytes):], yBytes)
		yBytes = padded
	}

	jwkMap := map[string]any{
		"kty": "EC",
		"crv": "P-256",
		"kid": kid,
		"use": "sig",
		"alg": "ES256",
		"x":   base64.RawURLEncoding.EncodeToString(xBytes),
		"y":   base64.RawURLEncoding.EncodeToString(yBytes),
	}

	jwks := map[string]any{
		"keys": []map[string]any{jwkMap},
	}
	data, err := json.Marshal(jwks)
	if err != nil {
		t.Fatalf("failed to marshal JWKS: %v", err)
	}
	return data
}
