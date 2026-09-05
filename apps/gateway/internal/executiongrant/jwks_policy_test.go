package executiongrant

import (
	"testing"
	"time"
)

func TestJWKSResolverPolicy_Validate(t *testing.T) {
	validPolicy := JWKSResolverPolicy{
		DefaultTTL:       60 * time.Second,
		MaxTTL:           300 * time.Second,
		RefreshCooldown:  5 * time.Second,
		RequestTimeout:   3 * time.Second,
		MaxResponseBytes: 64 * 1024,
	}

	t.Run("valid policy passes", func(t *testing.T) {
		if err := validPolicy.Validate(); err != nil {
			t.Fatalf("expected valid policy to pass, got: %v", err)
		}
	})

	t.Run("zero refresh cooldown passes", func(t *testing.T) {
		p := validPolicy
		p.RefreshCooldown = 0
		if err := p.Validate(); err != nil {
			t.Fatalf("expected zero refresh cooldown to pass, got: %v", err)
		}
	})

	t.Run("DefaultTTL <= 0 fails", func(t *testing.T) {
		p := validPolicy
		p.DefaultTTL = 0
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for DefaultTTL <= 0")
		}

		p.DefaultTTL = -1 * time.Second
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for negative DefaultTTL")
		}
	})

	t.Run("MaxTTL <= 0 fails", func(t *testing.T) {
		p := validPolicy
		p.MaxTTL = 0
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for MaxTTL <= 0")
		}

		p.MaxTTL = -1 * time.Second
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for negative MaxTTL")
		}
	})

	t.Run("DefaultTTL > MaxTTL fails", func(t *testing.T) {
		p := validPolicy
		p.DefaultTTL = 400 * time.Second
		p.MaxTTL = 300 * time.Second
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for DefaultTTL > MaxTTL")
		}
	})

	t.Run("RefreshCooldown < 0 fails", func(t *testing.T) {
		p := validPolicy
		p.RefreshCooldown = -1 * time.Second
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for negative RefreshCooldown")
		}
	})

	t.Run("RequestTimeout <= 0 fails", func(t *testing.T) {
		p := validPolicy
		p.RequestTimeout = 0
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for RequestTimeout <= 0")
		}

		p.RequestTimeout = -1 * time.Second
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for negative RequestTimeout")
		}
	})

	t.Run("MaxResponseBytes <= 0 fails", func(t *testing.T) {
		p := validPolicy
		p.MaxResponseBytes = 0
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for MaxResponseBytes <= 0")
		}

		p.MaxResponseBytes = -1
		if err := p.Validate(); err == nil {
			t.Fatalf("expected error for negative MaxResponseBytes")
		}
	})
}
