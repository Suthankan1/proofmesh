package runtime

import (
	"time"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
	httpexecutor "github.com/Suthankan1/proofmesh/apps/gateway/internal/toolexecutor/http"
)

// Config holds typed deployment configuration required to initialize the gateway Runtime.
// It is trusted deployment input and must never be bound directly from untrusted request payloads.
type Config struct {
	// DatabaseURL is the PostgreSQL connection string/DSN.
	DatabaseURL string

	// Issuer is the expected execution-grant issuer (iss).
	Issuer string

	// Audience is the expected execution-grant audience (aud).
	Audience string

	// JWKSURL is the HTTP/HTTPS endpoint URL where public keys are published.
	JWKSURL string

	// JWKSPolicy specifies caching and response limits for the JWKS resolver.
	// If zero-valued, DefaultJWKSResolverPolicy() is applied.
	JWKSPolicy executiongrant.JWKSResolverPolicy

	// ToolTargets contains trusted (ToolName, OperationName) -> URL mappings.
	ToolTargets []httpexecutor.Target

	// ToolPolicy specifies bounded execution limits for outbound HTTP invocations.
	// If zero-valued, httpexecutor defaults apply.
	ToolPolicy httpexecutor.Policy
}

// DefaultJWKSResolverPolicy returns safe operational defaults for JWKS resolution.
func DefaultJWKSResolverPolicy() executiongrant.JWKSResolverPolicy {
	return executiongrant.JWKSResolverPolicy{
		DefaultTTL:       5 * time.Minute,
		MaxTTL:           1 * time.Hour,
		RefreshCooldown:  10 * time.Second,
		RequestTimeout:   5 * time.Second,
		MaxResponseBytes: 1024 * 1024,
	}
}
