package runtime

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync"

	"github.com/jackc/pgx/v5/pgxpool"

	postgres "github.com/Suthankan1/proofmesh/apps/gateway/internal/executionauthority/postgres"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
	httpexecutor "github.com/Suthankan1/proofmesh/apps/gateway/internal/toolexecutor/http"
)

// Sentinel runtime errors. Infrastructure details, connection strings, credentials,
// and raw errors are strictly sanitized and never returned across this boundary.
var (
	ErrInvalidConfig           = errors.New("runtime: invalid configuration")
	ErrDatabaseUnavailable     = errors.New("runtime: database unavailable")
	ErrAuthorityInitialization = errors.New("runtime: authority initialization failed")
	ErrExecutorInitialization  = errors.New("runtime: tool executor initialization failed")
	ErrVerifierInitialization  = errors.New("runtime: verifier initialization failed")
	ErrEnforcerInitialization  = errors.New("runtime: enforcer initialization failed")
)

// Runtime is the composition root owning the gateway security pipeline and its database pool.
// It exposes only the Policy Enforcement Point (*pep.Enforcer) and lifecycle cleanup (Close).
// It intentionally does NOT expose raw tool executors, execution authorities, verifiers, or pools.
type Runtime struct {
	enforcer  *pep.Enforcer
	pool      *pgxpool.Pool
	transport *http.Transport
	closeOnce sync.Once
}

// runtimeDeps provides unexported dependency injection seams for deterministic testing.
type runtimeDeps struct {
	clock            executiongrant.Clock
	transport        http.RoundTripper
	poolFactory      func(ctx context.Context, connStr string) (*pgxpool.Pool, error)
	authorityFactory func(pool *pgxpool.Pool) (pep.ExecutionAuthority, error)
	executorFactory  func(targets []httpexecutor.Target, tr http.RoundTripper, policy httpexecutor.Policy) (pep.ToolExecutor, error)
	resolverFactory  func(endpointURL string, clock executiongrant.Clock, tr http.RoundTripper, policy executiongrant.JWKSResolverPolicy) (executiongrant.PublicKeyResolver, error)
	verifierFactory  func(issuer, audience string, clock executiongrant.Clock, resolver executiongrant.PublicKeyResolver) (pep.GrantVerifier, error)
	enforcerFactory  func(verifier pep.GrantVerifier, authority pep.ExecutionAuthority, executor pep.ToolExecutor, clock executiongrant.Clock) (*pep.Enforcer, error)
}

// New constructs and wires the production gateway Runtime using the provided configuration.
// It validates configuration early, connects and pings PostgreSQL, constructs durable authority,
// outbound protected HTTP executor, JWKS resolver, grant verifier, and the PEP enforcer.
// If construction fails after pool creation, the pool is closed before returning.
// Returned errors are strictly sanitized sentinel errors.
func New(ctx context.Context, cfg Config) (*Runtime, error) {
	return newRuntime(ctx, cfg, runtimeDeps{})
}

func newRuntime(ctx context.Context, cfg Config, deps runtimeDeps) (*Runtime, error) {
	if err := validateConfig(&cfg); err != nil {
		return nil, ErrInvalidConfig
	}

	clock := deps.clock
	if clock == nil {
		clock = executiongrant.SystemClock{}
	}

	var ownedTransport *http.Transport
	transport := deps.transport
	if transport == nil {
		ownedTransport = cloneDefaultTransport()
		transport = ownedTransport
	}

	poolFactory := deps.poolFactory
	if poolFactory == nil {
		poolFactory = pgxpool.New
	}

	authorityFactory := deps.authorityFactory
	if authorityFactory == nil {
		authorityFactory = func(p *pgxpool.Pool) (pep.ExecutionAuthority, error) {
			return postgres.NewAuthority(p)
		}
	}

	executorFactory := deps.executorFactory
	if executorFactory == nil {
		executorFactory = func(targets []httpexecutor.Target, tr http.RoundTripper, pol httpexecutor.Policy) (pep.ToolExecutor, error) {
			return httpexecutor.NewExecutor(targets, tr, pol)
		}
	}

	resolverFactory := deps.resolverFactory
	if resolverFactory == nil {
		resolverFactory = func(endpointURL string, clk executiongrant.Clock, tr http.RoundTripper, pol executiongrant.JWKSResolverPolicy) (executiongrant.PublicKeyResolver, error) {
			return executiongrant.NewJWKSResolver(endpointURL, clk, tr, pol)
		}
	}

	verifierFactory := deps.verifierFactory
	if verifierFactory == nil {
		verifierFactory = func(iss, aud string, clk executiongrant.Clock, res executiongrant.PublicKeyResolver) (pep.GrantVerifier, error) {
			return executiongrant.NewVerifier(iss, aud, clk, res)
		}
	}

	enforcerFactory := deps.enforcerFactory
	if enforcerFactory == nil {
		enforcerFactory = func(v pep.GrantVerifier, a pep.ExecutionAuthority, e pep.ToolExecutor, clk executiongrant.Clock) (*pep.Enforcer, error) {
			return pep.NewEnforcer(v, a, e, clk)
		}
	}

	// 1. Create pgxpool
	pool, err := poolFactory(ctx, cfg.DatabaseURL)
	if err != nil {
		if ownedTransport != nil {
			ownedTransport.CloseIdleConnections()
		}
		return nil, ErrDatabaseUnavailable
	}

	// 2. Startup ping to fail early on bad credentials or unreachable host
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		if ownedTransport != nil {
			ownedTransport.CloseIdleConnections()
		}
		return nil, ErrDatabaseUnavailable
	}

	// 3. Construct durable PostgreSQL ExecutionAuthority
	authority, err := authorityFactory(pool)
	if err != nil {
		pool.Close()
		if ownedTransport != nil {
			ownedTransport.CloseIdleConnections()
		}
		return nil, ErrAuthorityInitialization
	}

	// 4. Construct HTTP ToolExecutor with defensively snapshotted targets
	targetsCopy := make([]httpexecutor.Target, len(cfg.ToolTargets))
	copy(targetsCopy, cfg.ToolTargets)

	executor, err := executorFactory(targetsCopy, transport, cfg.ToolPolicy)
	if err != nil {
		pool.Close()
		if ownedTransport != nil {
			ownedTransport.CloseIdleConnections()
		}
		return nil, ErrExecutorInitialization
	}

	// 5. Construct JWKS resolver
	resolver, err := resolverFactory(cfg.JWKSURL, clock, transport, cfg.JWKSPolicy)
	if err != nil {
		pool.Close()
		if ownedTransport != nil {
			ownedTransport.CloseIdleConnections()
		}
		return nil, ErrVerifierInitialization
	}

	// 6. Construct execution-grant Verifier
	verifier, err := verifierFactory(cfg.Issuer, cfg.Audience, clock, resolver)
	if err != nil {
		pool.Close()
		if ownedTransport != nil {
			ownedTransport.CloseIdleConnections()
		}
		return nil, ErrVerifierInitialization
	}

	// 7. Construct PEP Enforcer
	enforcer, err := enforcerFactory(verifier, authority, executor, clock)
	if err != nil {
		pool.Close()
		if ownedTransport != nil {
			ownedTransport.CloseIdleConnections()
		}
		return nil, ErrEnforcerInitialization
	}

	return &Runtime{
		enforcer:  enforcer,
		pool:      pool,
		transport: ownedTransport,
	}, nil
}

// Enforcer returns the initialized Policy Enforcement Point orchestrator.
// If the Runtime is nil, it returns nil.
func (r *Runtime) Enforcer() *pep.Enforcer {
	if r == nil {
		return nil
	}
	return r.enforcer
}

// Close idempotently releases resources owned by Runtime, specifically closing the database pool
// and closing idle connections on any runtime-owned HTTP transport.
// It is safe to call on a nil receiver and safe for repeated calls.
func (r *Runtime) Close() {
	if r == nil {
		return
	}
	r.closeOnce.Do(func() {
		if r.pool != nil {
			r.pool.Close()
		}
		if r.transport != nil {
			r.transport.CloseIdleConnections()
		}
	})
}

func validateConfig(cfg *Config) error {
	if cfg == nil {
		return errors.New("nil config")
	}
	if strings.TrimSpace(cfg.DatabaseURL) == "" {
		return errors.New("empty database URL")
	}
	if strings.TrimSpace(cfg.Issuer) == "" {
		return errors.New("empty issuer")
	}
	if strings.TrimSpace(cfg.Audience) == "" {
		return errors.New("empty audience")
	}
	if strings.TrimSpace(cfg.JWKSURL) == "" {
		return errors.New("empty JWKS URL")
	}
	if len(cfg.ToolTargets) == 0 {
		return errors.New("empty tool targets")
	}
	for _, t := range cfg.ToolTargets {
		if strings.TrimSpace(t.ToolName) == "" || strings.TrimSpace(t.OperationName) == "" {
			return errors.New("invalid tool target name or operation")
		}
	}
	if cfg.ToolPolicy.RequestTimeout < 0 || cfg.ToolPolicy.MaxResponseBytes < 0 {
		return errors.New("invalid tool policy bounds")
	}
	if cfg.JWKSPolicy == (executiongrant.JWKSResolverPolicy{}) {
		cfg.JWKSPolicy = DefaultJWKSResolverPolicy()
	} else {
		if err := cfg.JWKSPolicy.Validate(); err != nil {
			return err
		}
	}
	return nil
}

func cloneDefaultTransport() *http.Transport {
	if dt, ok := http.DefaultTransport.(*http.Transport); ok {
		return dt.Clone()
	}
	return &http.Transport{}
}
