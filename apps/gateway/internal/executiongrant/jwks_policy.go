package executiongrant

import (
	"errors"
	"time"
)

// JWKSResolverPolicy configures TTL, timeout, cooldown, and response limits for JWKS resolution.
type JWKSResolverPolicy struct {
	DefaultTTL       time.Duration
	MaxTTL           time.Duration
	RefreshCooldown  time.Duration
	RequestTimeout   time.Duration
	MaxResponseBytes int64
}

// Validate validates that all policy parameters satisfy security and correctness bounds.
func (p JWKSResolverPolicy) Validate() error {
	if p.DefaultTTL <= 0 {
		return errors.New("default TTL must be positive")
	}
	if p.MaxTTL <= 0 {
		return errors.New("max TTL must be positive")
	}
	if p.DefaultTTL > p.MaxTTL {
		return errors.New("default TTL must not exceed max TTL")
	}
	if p.RefreshCooldown < 0 {
		return errors.New("refresh cooldown must be non-negative")
	}
	if p.RequestTimeout <= 0 {
		return errors.New("request timeout must be positive")
	}
	if p.MaxResponseBytes <= 0 {
		return errors.New("max response bytes must be positive")
	}
	return nil
}
