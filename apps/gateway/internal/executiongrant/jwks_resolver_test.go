package executiongrant

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/lestrrat-go/jwx/v3/jwa"
)

type mutableClock struct {
	mu  sync.RWMutex
	now time.Time
}

func newMutableClock(t time.Time) *mutableClock {
	return &mutableClock{now: t.UTC()}
}

func (c *mutableClock) Now() time.Time {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.now
}

func (c *mutableClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

func defaultTestPolicy() JWKSResolverPolicy {
	return JWKSResolverPolicy{
		DefaultTTL:       60 * time.Second,
		MaxTTL:           300 * time.Second,
		RefreshCooldown:  5 * time.Second,
		RequestTimeout:   3 * time.Second,
		MaxResponseBytes: 64 * 1024,
	}
}

func ecPublicKeyToJWKMap(pubKey *ecdsa.PublicKey, kid string) map[string]any {
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

	return map[string]any{
		"kty": "EC",
		"crv": "P-256",
		"kid": kid,
		"use": "sig",
		"alg": "ES256",
		"x":   base64.RawURLEncoding.EncodeToString(xBytes),
		"y":   base64.RawURLEncoding.EncodeToString(yBytes),
	}
}

func makeJWKSBytes(t *testing.T, keyMaps ...map[string]any) []byte {
	t.Helper()
	m := map[string]any{
		"keys": keyMaps,
	}
	data, err := json.Marshal(m)
	if err != nil {
		t.Fatalf("failed to marshal JWKS: %v", err)
	}
	return data
}

// -----------------------------------------------------------------------------
// Constructor tests
// -----------------------------------------------------------------------------

func TestNewJWKSResolver_Validation(t *testing.T) {
	clock := SystemClock{}
	policy := defaultTestPolicy()

	tests := []struct {
		name        string
		endpointURL string
		clock       Clock
		policy      JWKSResolverPolicy
		wantErr     string
	}{
		{
			name:        "empty endpoint URL",
			endpointURL: "",
			clock:       clock,
			policy:      policy,
			wantErr:     "endpoint URL must not be empty",
		},
		{
			name:        "whitespace endpoint URL",
			endpointURL: "   ",
			clock:       clock,
			policy:      policy,
			wantErr:     "endpoint URL must not be empty",
		},
		{
			name:        "relative URL",
			endpointURL: "/.well-known/jwks.json",
			clock:       clock,
			policy:      policy,
			wantErr:     "endpoint URL must be absolute",
		},
		{
			name:        "invalid scheme ftp",
			endpointURL: "ftp://example.com/jwks.json",
			clock:       clock,
			policy:      policy,
			wantErr:     "endpoint URL scheme must be http or https",
		},
		{
			name:        "invalid scheme file",
			endpointURL: "file:///jwks.json",
			clock:       clock,
			policy:      policy,
			wantErr:     "endpoint URL scheme must be http or https",
		},
		{
			name:        "empty host",
			endpointURL: "http:///jwks.json",
			clock:       clock,
			policy:      policy,
			wantErr:     "endpoint URL host must not be empty",
		},
		{
			name:        "userinfo rejected",
			endpointURL: "http://admin:secret@example.com/jwks.json",
			clock:       clock,
			policy:      policy,
			wantErr:     "userinfo",
		},
		{
			name:        "fragment rejected",
			endpointURL: "http://example.com/jwks.json#frag",
			clock:       clock,
			policy:      policy,
			wantErr:     "fragment",
		},
		{
			name:        "nil clock",
			endpointURL: "http://example.com/jwks.json",
			clock:       nil,
			policy:      policy,
			wantErr:     "clock must not be nil",
		},
		{
			name:        "invalid policy",
			endpointURL: "http://example.com/jwks.json",
			clock:       clock,
			policy: JWKSResolverPolicy{
				DefaultTTL: 0,
			},
			wantErr: "default TTL must be positive",
		},
		{
			name:        "valid http endpoint",
			endpointURL: "http://example.com:8080/path/to/jwks.json?tenant=abc",
			clock:       clock,
			policy:      policy,
			wantErr:     "",
		},
		{
			name:        "valid https endpoint",
			endpointURL: "https://auth.example.com/.well-known/jwks.json",
			clock:       clock,
			policy:      policy,
			wantErr:     "",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			resolver, err := NewJWKSResolver(tc.endpointURL, tc.clock, nil, tc.policy)
			if tc.wantErr != "" {
				if err == nil {
					t.Fatalf("expected error containing %q, got nil", tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if resolver == nil {
				t.Fatalf("expected non-nil resolver")
			}
		})
	}
}

// -----------------------------------------------------------------------------
// HTTP behavior tests
// -----------------------------------------------------------------------------

func TestJWKSResolver_HTTPBehavior(t *testing.T) {
	privKey := generateP256Key(t)
	validJWKS := makeJWKSBytes(t, ecPublicKeyToJWKMap(&privKey.PublicKey, "test-kid"))

	t.Run("exact GET method and path and Accept header", func(t *testing.T) {
		var (
			gotMethod string
			gotPath   string
			gotAccept string
		)
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotMethod = r.Method
			gotPath = r.URL.Path
			gotAccept = r.Header.Get("Accept")
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(validJWKS)
		}))
		defer srv.Close()

		resolver, err := NewJWKSResolver(srv.URL+"/custom/jwks.json", SystemClock{}, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		key, err := resolver.ResolveExecutionGrantKey(context.Background(), "test-kid")
		if err != nil {
			t.Fatalf("resolve failed: %v", err)
		}
		if key == nil {
			t.Fatalf("expected resolved key")
		}
		if gotMethod != http.MethodGet {
			t.Fatalf("expected GET, got: %s", gotMethod)
		}
		if gotPath != "/custom/jwks.json" {
			t.Fatalf("expected /custom/jwks.json, got: %s", gotPath)
		}
		if gotAccept != "application/jwk-set+json, application/json" {
			t.Fatalf("unexpected Accept header: %s", gotAccept)
		}
	})

	t.Run("redirect refused fails closed", func(t *testing.T) {
		redirectTarget := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(validJWKS)
		}))
		defer redirectTarget.Close()

		redirector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, redirectTarget.URL, http.StatusFound)
		}))
		defer redirector.Close()

		resolver, err := NewJWKSResolver(redirector.URL, SystemClock{}, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "test-kid")
		if !errors.Is(err, ErrKeyResolutionFailed) {
			t.Fatalf("expected ErrKeyResolutionFailed on redirect, got: %v", err)
		}
	})

	t.Run("non-200 HTTP status fails closed", func(t *testing.T) {
		statusCodes := []int{
			http.StatusBadRequest,
			http.StatusUnauthorized,
			http.StatusForbidden,
			http.StatusNotFound,
			http.StatusInternalServerError,
			http.StatusBadGateway,
			http.StatusServiceUnavailable,
		}

		for _, code := range statusCodes {
			t.Run(fmt.Sprintf("status_%d", code), func(t *testing.T) {
				srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					w.WriteHeader(code)
					_, _ = w.Write([]byte("error response"))
				}))
				defer srv.Close()

				resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, defaultTestPolicy())
				if err != nil {
					t.Fatalf("NewJWKSResolver failed: %v", err)
				}

				_, err = resolver.ResolveExecutionGrantKey(context.Background(), "test-kid")
				if !errors.Is(err, ErrKeyResolutionFailed) {
					t.Fatalf("expected ErrKeyResolutionFailed for status %d, got: %v", code, err)
				}
			})
		}
	})

	t.Run("bad or missing Content-Type fails closed", func(t *testing.T) {
		contentTypes := []string{
			"",
			"text/plain",
			"text/html",
			"application/xml",
			"application/octet-stream",
			"invalid-content-type",
		}

		for _, ct := range contentTypes {
			t.Run(fmt.Sprintf("ct_%s", ct), func(t *testing.T) {
				srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					if ct != "" {
						w.Header().Set("Content-Type", ct)
					}
					w.WriteHeader(http.StatusOK)
					_, _ = w.Write(validJWKS)
				}))
				defer srv.Close()

				resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, defaultTestPolicy())
				if err != nil {
					t.Fatalf("NewJWKSResolver failed: %v", err)
				}

				_, err = resolver.ResolveExecutionGrantKey(context.Background(), "test-kid")
				if !errors.Is(err, ErrKeyResolutionFailed) {
					t.Fatalf("expected ErrKeyResolutionFailed for content-type %q, got: %v", ct, err)
				}
			})
		}
	})

	t.Run("valid Content-Type with parameters accepted", func(t *testing.T) {
		accepted := []string{
			"application/json",
			"application/json; charset=utf-8",
			"application/jwk-set+json",
			"application/jwk-set+json; charset=UTF-8",
		}

		for _, ct := range accepted {
			t.Run(ct, func(t *testing.T) {
				srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					w.Header().Set("Content-Type", ct)
					w.WriteHeader(http.StatusOK)
					_, _ = w.Write(validJWKS)
				}))
				defer srv.Close()

				resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, defaultTestPolicy())
				if err != nil {
					t.Fatalf("NewJWKSResolver failed: %v", err)
				}

				key, err := resolver.ResolveExecutionGrantKey(context.Background(), "test-kid")
				if err != nil {
					t.Fatalf("expected success for Content-Type %q, got: %v", ct, err)
				}
				if key == nil {
					t.Fatalf("expected resolved key")
				}
			})
		}
	})

	t.Run("oversized response body fails closed", func(t *testing.T) {
		policy := defaultTestPolicy()
		policy.MaxResponseBytes = 100 // small limit

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(validJWKS) // len(validJWKS) is > 100 bytes
		}))
		defer srv.Close()

		resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, policy)
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "test-kid")
		if !errors.Is(err, ErrKeyResolutionFailed) {
			t.Fatalf("expected ErrKeyResolutionFailed on oversized response, got: %v", err)
		}
	})

	t.Run("caller cancellation fails closed", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// delay response
			time.Sleep(100 * time.Millisecond)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(validJWKS)
		}))
		defer srv.Close()

		resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		ctx, cancel := context.WithCancel(context.Background())
		cancel() // cancel immediately

		_, err = resolver.ResolveExecutionGrantKey(ctx, "test-kid")
		if !errors.Is(err, ErrKeyResolutionFailed) {
			t.Fatalf("expected ErrKeyResolutionFailed on cancelled context, got: %v", err)
		}
	})

	t.Run("request timeout fails closed", func(t *testing.T) {
		policy := defaultTestPolicy()
		policy.RequestTimeout = 50 * time.Millisecond

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			time.Sleep(200 * time.Millisecond)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(validJWKS)
		}))
		defer srv.Close()

		resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, policy)
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "test-kid")
		if !errors.Is(err, ErrKeyResolutionFailed) {
			t.Fatalf("expected ErrKeyResolutionFailed on timeout, got: %v", err)
		}
	})
}

// -----------------------------------------------------------------------------
// JWKS schema & JWK validation tests
// -----------------------------------------------------------------------------

func TestJWKSResolver_SchemaAndKeyValidation(t *testing.T) {
	privKey := generateP256Key(t)
	validKeyMap := ecPublicKeyToJWKMap(&privKey.PublicKey, "valid-kid-1")

	tests := []struct {
		name        string
		body        []byte
		expectError bool
	}{
		{
			name:        "malformed JSON",
			body:        []byte(`{"keys": [}`),
			expectError: true,
		},
		{
			name:        "duplicate keys member at root",
			body:        []byte(fmt.Sprintf(`{"keys":[%s],"keys":[%s]}`, string(mustJSON(t, validKeyMap)), string(mustJSON(t, validKeyMap)))),
			expectError: true,
		},
		{
			name: "duplicate member inside key object",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "k-dup")
				b, _ := json.Marshal(m)
				// Inject duplicate kty
				raw := strings.TrimSuffix(string(b), "}") + `,"kty":"EC"}`
				return []byte(`{"keys":[` + raw + `]}`)
			}(),
			expectError: true,
		},
		{
			name:        "missing keys member",
			body:        []byte(`{"other": 123}`),
			expectError: true,
		},
		{
			name:        "keys is not an array",
			body:        []byte(`{"keys": "not-an-array"}`),
			expectError: true,
		},
		{
			name:        "empty keys array",
			body:        []byte(`{"keys": []}`),
			expectError: true,
		},
		{
			name:        "null entry in keys array",
			body:        []byte(`{"keys": [null]}`),
			expectError: true,
		},
		{
			name:        "string entry in keys array",
			body:        []byte(`{"keys": ["invalid-string"]}`),
			expectError: true,
		},
		{
			name:        "number entry in keys array",
			body:        []byte(`{"keys": [12345]}`),
			expectError: true,
		},
		{
			name: "private d member present rejects entire set",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "priv-kid")
				m["d"] = base64.RawURLEncoding.EncodeToString(privKey.D.Bytes())
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "wrong kty RSA",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "rsa-kid")
				m["kty"] = "RSA"
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "wrong crv P-384",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "p384-kid")
				m["crv"] = "P-384"
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "wrong use enc",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "enc-kid")
				m["use"] = "enc"
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "wrong alg RS256",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "rs-kid")
				m["alg"] = "RS256"
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "blank kid empty string",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "")
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "blank kid whitespace only",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "   ")
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "duplicate kid in keys array",
			body: func() []byte {
				m1 := ecPublicKeyToJWKMap(&privKey.PublicKey, "dup-kid")
				m2 := ecPublicKeyToJWKMap(&privKey.PublicKey, "dup-kid")
				return makeJWKSBytes(t, m1, m2)
			}(),
			expectError: true,
		},
		{
			name: "missing x coordinate",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "no-x")
				delete(m, "x")
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "missing y coordinate",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "no-y")
				delete(m, "y")
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "point not on P-256 curve",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "off-curve")
				// corrupt x coordinate
				m["x"] = base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{0x01}, 32))
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "x coordinate is 31 bytes (non-canonical)",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "x-31")
				m["x"] = base64.RawURLEncoding.EncodeToString(make([]byte, 31))
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "x coordinate is 33 bytes (non-canonical)",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "x-33")
				m["x"] = base64.RawURLEncoding.EncodeToString(make([]byte, 33))
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "y coordinate is 31 bytes (non-canonical)",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "y-31")
				m["y"] = base64.RawURLEncoding.EncodeToString(make([]byte, 31))
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "y coordinate is 33 bytes (non-canonical)",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "y-33")
				m["y"] = base64.RawURLEncoding.EncodeToString(make([]byte, 33))
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "x coordinate is 31 bytes (truncated valid coordinate)",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "x-31-trunc")
				rawX, _ := base64.RawURLEncoding.DecodeString(m["x"].(string))
				m["x"] = base64.RawURLEncoding.EncodeToString(rawX[:31])
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "x coordinate is 33 bytes (leading zero non-canonical)",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "x-33-zero")
				rawX, _ := base64.RawURLEncoding.DecodeString(m["x"].(string))
				m["x"] = base64.RawURLEncoding.EncodeToString(append([]byte{0x00}, rawX...))
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "y coordinate is 31 bytes (truncated valid coordinate)",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "y-31-trunc")
				rawY, _ := base64.RawURLEncoding.DecodeString(m["y"].(string))
				m["y"] = base64.RawURLEncoding.EncodeToString(rawY[:31])
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name: "y coordinate is 33 bytes (leading zero non-canonical)",
			body: func() []byte {
				m := ecPublicKeyToJWKMap(&privKey.PublicKey, "y-33-zero")
				rawY, _ := base64.RawURLEncoding.DecodeString(m["y"].(string))
				m["y"] = base64.RawURLEncoding.EncodeToString(append([]byte{0x00}, rawY...))
				return makeJWKSBytes(t, m)
			}(),
			expectError: true,
		},
		{
			name:        "valid one-key set",
			body:        makeJWKSBytes(t, validKeyMap),
			expectError: false,
		},
		{
			name: "valid multi-key set",
			body: func() []byte {
				k2 := generateP256Key(t)
				k3 := generateP256Key(t)
				m1 := ecPublicKeyToJWKMap(&privKey.PublicKey, "valid-kid-1")
				m2 := ecPublicKeyToJWKMap(&k2.PublicKey, "valid-kid-2")
				m3 := ecPublicKeyToJWKMap(&k3.PublicKey, "valid-kid-3")
				return makeJWKSBytes(t, m1, m2, m3)
			}(),
			expectError: false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write(tc.body)
			}))
			defer srv.Close()

			resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, defaultTestPolicy())
			if err != nil {
				t.Fatalf("NewJWKSResolver failed: %v", err)
			}

			key, err := resolver.ResolveExecutionGrantKey(context.Background(), "valid-kid-1")
			if tc.expectError {
				if err == nil {
					t.Fatalf("expected error, got success with key: %v", key)
				}
				if !errors.Is(err, ErrKeyResolutionFailed) {
					t.Fatalf("expected ErrKeyResolutionFailed, got: %v", err)
				}
			} else {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if key == nil {
					t.Fatalf("expected key, got nil")
				}
				if key.Curve != elliptic.P256() {
					t.Fatalf("expected P-256 curve, got: %v", key.Curve)
				}
			}
		})
	}
}

// -----------------------------------------------------------------------------
// Cache & Refresh & Cooldown behavior tests
// -----------------------------------------------------------------------------

func TestJWKSResolver_CacheAndCooldownBehavior(t *testing.T) {
	key1 := generateP256Key(t)
	key2 := generateP256Key(t)
	map1 := ecPublicKeyToJWKMap(&key1.PublicKey, "kid-1")
	map2 := ecPublicKeyToJWKMap(&key2.PublicKey, "kid-2")

	t.Run("fresh hit does not perform second HTTP request", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		// First call fetches from server
		k1, err := resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil || k1 == nil {
			t.Fatalf("first lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected 1 request, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Advance clock by 30 seconds (still within 60s TTL)
		clock.Advance(30 * time.Second)

		// Second call must hit fresh cache without HTTP request
		k2, err := resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil || k2 == nil {
			t.Fatalf("second lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected request count to remain 1, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("expiry causes mandatory refresh", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("initial lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected 1 request, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Advance clock past 60s TTL
		clock.Advance(61 * time.Second)

		// Third call triggers refresh
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("post-expiry lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected 2 requests after expiry, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("max-age=0 is immediately stale on next call", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=0")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		policy := defaultTestPolicy()
		policy.RefreshCooldown = 0 // allow immediate refresh
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, policy)
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		// First call succeeds
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("first lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected 1 request, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Next call must refresh immediately
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("second lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected 2 requests for max-age=0, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("max-age above MaxTTL is capped", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=600") // 600s
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		policy := defaultTestPolicy()
		policy.MaxTTL = 100 * time.Second // cap at 100s

		resolver, err := NewJWKSResolver(srv.URL, clock, nil, policy)
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("initial lookup failed: %v", err)
		}

		// Advance 90s (within 100s cap) => hit
		clock.Advance(90 * time.Second)
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected 1 request, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Advance 20s (total 110s > 100s cap) => must refresh
		clock.Advance(20 * time.Second)
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected 2 requests after MaxTTL cap exceeded, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("missing max-age uses DefaultTTL", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			// No Cache-Control header
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		policy := defaultTestPolicy()
		policy.DefaultTTL = 45 * time.Second

		resolver, err := NewJWKSResolver(srv.URL, clock, nil, policy)
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("initial lookup failed: %v", err)
		}

		// Advance 30s (less than DefaultTTL 45s) => hit
		clock.Advance(30 * time.Second)
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected 1 request, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Advance 20s (total 50s > 45s) => refresh
		clock.Advance(20 * time.Second)
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected 2 requests, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("malformed max-age cannot extend cache", func(t *testing.T) {
		malformedDirectives := []string{
			"max-age=invalid",
			"max-age=-50",
			"max-age=999999999999999999999999999999",
		}

		for _, directive := range malformedDirectives {
			t.Run(directive, func(t *testing.T) {
				var requestCount int32
				srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					atomic.AddInt32(&requestCount, 1)
					w.Header().Set("Content-Type", "application/json")
					w.Header().Set("Cache-Control", directive)
					w.WriteHeader(http.StatusOK)
					_, _ = w.Write(makeJWKSBytes(t, map1))
				}))
				defer srv.Close()

				clock := newMutableClock(time.Now())
				policy := defaultTestPolicy()
				policy.DefaultTTL = 30 * time.Second

				resolver, err := NewJWKSResolver(srv.URL, clock, nil, policy)
				if err != nil {
					t.Fatalf("NewJWKSResolver failed: %v", err)
				}

				_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
				if err != nil {
					t.Fatalf("initial lookup failed: %v", err)
				}

				// Advance past DefaultTTL (31s)
				clock.Advance(31 * time.Second)
				_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
				if err != nil {
					t.Fatalf("lookup failed: %v", err)
				}
				if atomic.LoadInt32(&requestCount) != 2 {
					t.Fatalf("expected 2 requests for malformed Cache-Control, got: %d", atomic.LoadInt32(&requestCount))
				}
			})
		}
	})

	t.Run("duplicate max-age directives choose minimum and are order-independent", func(t *testing.T) {
		for _, headerVal := range []string{"max-age=600, max-age=10", "max-age=10, max-age=600"} {
			t.Run(headerVal, func(t *testing.T) {
				var requestCount int32
				srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					atomic.AddInt32(&requestCount, 1)
					w.Header().Set("Content-Type", "application/json")
					w.Header().Set("Cache-Control", headerVal)
					w.WriteHeader(http.StatusOK)
					_, _ = w.Write(makeJWKSBytes(t, map1))
				}))
				defer srv.Close()

				clock := newMutableClock(time.Now())
				resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
				if err != nil {
					t.Fatalf("NewJWKSResolver failed: %v", err)
				}

				_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
				if err != nil {
					t.Fatalf("initial lookup failed: %v", err)
				}

				// Within 10s: hit
				clock.Advance(9 * time.Second)
				_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
				if err != nil {
					t.Fatalf("cached lookup failed: %v", err)
				}
				if atomic.LoadInt32(&requestCount) != 1 {
					t.Fatalf("expected 1 request within 10s, got: %d", atomic.LoadInt32(&requestCount))
				}

				// Past 10s: refresh
				clock.Advance(2 * time.Second)
				_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
				if err != nil {
					t.Fatalf("post-expiry lookup failed: %v", err)
				}
				if atomic.LoadInt32(&requestCount) != 2 {
					t.Fatalf("expected 2 requests after 10s expiry, got: %d", atomic.LoadInt32(&requestCount))
				}
			})
		}
	})

	t.Run("multiple Cache-Control header lines choose conservative minimum", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Add("Cache-Control", "max-age=600")
			w.Header().Add("Cache-Control", "max-age=10")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("initial lookup failed: %v", err)
		}

		// Within 10s: hit
		clock.Advance(9 * time.Second)
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("cached lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected 1 request within 10s, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Past 10s: refresh
		clock.Advance(2 * time.Second)
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("post-expiry lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected 2 requests after 10s expiry, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("malformed conflict never extends beyond DefaultTTL in HTTP server", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=600, max-age=bogus")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		policy := defaultTestPolicy()
		policy.DefaultTTL = 30 * time.Second
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, policy)
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("initial lookup failed: %v", err)
		}

		// Advance past DefaultTTL (30s): must refresh, not extended to 600s
		clock.Advance(31 * time.Second)
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected 2 requests after DefaultTTL expiry, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("exact expiry equality now == expiresAt is considered stale", func(t *testing.T) {
		var shouldFail int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if atomic.LoadInt32(&shouldFail) == 1 {
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		// Initial success
		key, err := resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil || key == nil {
			t.Fatalf("initial lookup failed: %v", err)
		}

		// Advance clock by exactly 60s (now == expiresAt)
		clock.Advance(60 * time.Second)
		atomic.StoreInt32(&shouldFail, 1) // Server now fails

		// Must fail closed and NEVER serve stale key at exact boundary
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if !errors.Is(err, ErrKeyResolutionFailed) {
			t.Fatalf("expected ErrKeyResolutionFailed on exact expiry boundary refresh failure, got: %v", err)
		}
	})

	t.Run("stale cache and network failure fails closed", func(t *testing.T) {
		var shouldFail int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if atomic.LoadInt32(&shouldFail) == 1 {
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		// Initial success
		key, err := resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil || key == nil {
			t.Fatalf("initial lookup failed: %v", err)
		}

		// Advance clock past TTL to make cache stale
		clock.Advance(61 * time.Second)
		atomic.StoreInt32(&shouldFail, 1) // Server now fails

		// Must fail closed and NEVER serve stale key
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if !errors.Is(err, ErrKeyResolutionFailed) {
			t.Fatalf("expected ErrKeyResolutionFailed on stale cache refresh failure, got: %v", err)
		}
	})

	t.Run("fresh known key works without network when server is down", func(t *testing.T) {
		var shouldFail int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if atomic.LoadInt32(&shouldFail) == 1 {
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		// Populate cache
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("initial lookup failed: %v", err)
		}

		// Server goes down, but cache is still fresh
		atomic.StoreInt32(&shouldFail, 1)
		clock.Advance(10 * time.Second) // 10s < 60s

		key, err := resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil || key == nil {
			t.Fatalf("expected fresh key lookup to succeed without network, got: %v", err)
		}
	})

	t.Run("unknown kid triggers bounded refresh", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			count := atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			if count == 1 {
				// First response contains only kid-1
				_, _ = w.Write(makeJWKSBytes(t, map1))
			} else {
				// Second response contains kid-1 and kid-2
				_, _ = w.Write(makeJWKSBytes(t, map1, map2))
			}
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		// Initial lookup for kid-1
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("kid-1 lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected 1 request, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Request for unknown kid-2 triggers bounded refresh opportunity
		key2, err := resolver.ResolveExecutionGrantKey(context.Background(), "kid-2")
		if err != nil || key2 == nil {
			t.Fatalf("kid-2 lookup after refresh failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected 2 requests after unknown-kid refresh, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("repeated random kids within cooldown do not hammer endpoint", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		clock := newMutableClock(time.Now())
		policy := defaultTestPolicy()
		policy.RefreshCooldown = 10 * time.Second

		resolver, err := NewJWKSResolver(srv.URL, clock, nil, policy)
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		// Initial lookup for kid-1
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil {
			t.Fatalf("initial lookup failed: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected 1 request, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Lookup for unknown kid-random-1 triggers 1 refresh opportunity
		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "kid-random-1")
		if !errors.Is(err, ErrUnknownKey) {
			t.Fatalf("expected ErrUnknownKey, got: %v", err)
		}
		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected 2 requests after first random kid, got: %d", atomic.LoadInt32(&requestCount))
		}

		// Repeated lookups for random kids within 10s cooldown must NOT trigger HTTP requests
		for i := 2; i <= 10; i++ {
			randomKID := fmt.Sprintf("kid-random-%d", i)
			_, err = resolver.ResolveExecutionGrantKey(context.Background(), randomKID)
			if !errors.Is(err, ErrUnknownKey) {
				t.Fatalf("expected ErrUnknownKey during cooldown, got: %v", err)
			}
		}

		if atomic.LoadInt32(&requestCount) != 2 {
			t.Fatalf("expected request count to remain 2 during cooldown, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("concurrent misses cause one serialized GET", func(t *testing.T) {
		var requestCount int32
		serverEntered := make(chan struct{})
		allowServerProceed := make(chan struct{})

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			close(serverEntered)
			<-allowServerProceed
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		const numGoroutines = 15
		var wg sync.WaitGroup
		wg.Add(numGoroutines)

		// Start goroutine 1
		go func() {
			defer wg.Done()
			_, _ = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		}()

		// Wait until goroutine 1 hits the server handler
		<-serverEntered

		// Start remaining goroutines while goroutine 1 is inside the HTTP handler
		for i := 1; i < numGoroutines; i++ {
			go func() {
				defer wg.Done()
				_, _ = resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
			}()
		}

		// Let server proceed
		close(allowServerProceed)
		wg.Wait()

		if atomic.LoadInt32(&requestCount) != 1 {
			t.Fatalf("expected exactly 1 serialized HTTP GET, got: %d", atomic.LoadInt32(&requestCount))
		}
	})

	t.Run("returned key mutation cannot corrupt cache", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "max-age=60")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(makeJWKSBytes(t, map1))
		}))
		defer srv.Close()

		resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		k1, err := resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil || k1 == nil {
			t.Fatalf("initial lookup failed: %v", err)
		}

		originalX := new(big.Int).Set(k1.X)

		// Mutate returned key
		k1.X.SetInt64(0)
		k1.Y.SetInt64(0)
		k1.Curve = nil

		// Fetch again from cache
		k2, err := resolver.ResolveExecutionGrantKey(context.Background(), "kid-1")
		if err != nil || k2 == nil {
			t.Fatalf("second lookup failed: %v", err)
		}

		if k2.Curve != elliptic.P256() {
			t.Fatalf("cached key curve was corrupted")
		}
		if k2.X.Cmp(originalX) != 0 {
			t.Fatalf("cached key X coordinate was corrupted: expected %s, got %s", originalX.String(), k2.X.String())
		}
	})

	t.Run("blank kid lookup rejected without network", func(t *testing.T) {
		var requestCount int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&requestCount, 1)
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()

		resolver, err := NewJWKSResolver(srv.URL, SystemClock{}, nil, defaultTestPolicy())
		if err != nil {
			t.Fatalf("NewJWKSResolver failed: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "")
		if !errors.Is(err, ErrUnknownKey) {
			t.Fatalf("expected ErrUnknownKey for empty kid, got: %v", err)
		}

		_, err = resolver.ResolveExecutionGrantKey(context.Background(), "   ")
		if !errors.Is(err, ErrUnknownKey) {
			t.Fatalf("expected ErrUnknownKey for whitespace kid, got: %v", err)
		}

		if atomic.LoadInt32(&requestCount) != 0 {
			t.Fatalf("expected 0 requests for blank kid, got: %d", atomic.LoadInt32(&requestCount))
		}
	})
}

// -----------------------------------------------------------------------------
// Verifier integration test
// -----------------------------------------------------------------------------

func TestJWKSResolver_VerifierIntegration(t *testing.T) {
	key := generateP256Key(t)
	kid := "active-key-2026"
	keyMap := ecPublicKeyToJWKMap(&key.PublicKey, kid)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "public, max-age=60, must-revalidate")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(makeJWKSBytes(t, keyMap))
	}))
	defer srv.Close()

	clock := newMutableClock(time.Now())
	resolver, err := NewJWKSResolver(srv.URL, clock, nil, defaultTestPolicy())
	if err != nil {
		t.Fatalf("NewJWKSResolver failed: %v", err)
	}

	verifier, err := NewVerifier(testIssuer, testAudience, clock, resolver)
	if err != nil {
		t.Fatalf("NewVerifier failed: %v", err)
	}

	t.Run("valid token verified successfully through real JWKS resolver", func(t *testing.T) {
		claims := defaultClaims(clock.Now())
		token := buildCompactToken(t, key, jwa.ES256(), ExpectedGrantType, kid, claims)

		verified, err := verifier.Verify(context.Background(), token)
		if err != nil {
			t.Fatalf("expected verification to succeed, got: %v", err)
		}

		if verified.KeyID != kid {
			t.Fatalf("expected key ID %s, got: %s", kid, verified.KeyID)
		}
		if verified.Issuer != testIssuer {
			t.Fatalf("expected issuer %s, got: %s", testIssuer, verified.Issuer)
		}
		if verified.Audience != testAudience {
			t.Fatalf("expected audience %s, got: %s", testAudience, verified.Audience)
		}
	})

	t.Run("unknown kid fails closed", func(t *testing.T) {
		claims := defaultClaims(clock.Now())
		token := buildCompactToken(t, key, jwa.ES256(), ExpectedGrantType, "unknown-kid-xyz", claims)

		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrUnknownKey) {
			t.Fatalf("expected ErrUnknownKey for unknown kid, got: %v", err)
		}
	})

	t.Run("invalid signature fails closed", func(t *testing.T) {
		wrongKey := generateP256Key(t)
		claims := defaultClaims(clock.Now())
		// Sign with wrong private key, but claim the kid that resolves to key.PublicKey
		token := buildCompactToken(t, wrongKey, jwa.ES256(), ExpectedGrantType, kid, claims)

		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidSignature) {
			t.Fatalf("expected ErrInvalidSignature, got: %v", err)
		}
	})
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("failed to marshal JSON: %v", err)
	}
	return b
}

func TestJWKSResolver_ParseCacheControlTTL(t *testing.T) {
	policy := JWKSResolverPolicy{
		DefaultTTL:       60 * time.Second,
		MaxTTL:           300 * time.Second,
		RefreshCooldown:  5 * time.Second,
		RequestTimeout:   3 * time.Second,
		MaxResponseBytes: 64 * 1024,
	}

	tests := []struct {
		name     string
		headers  http.Header
		expected time.Duration
	}{
		{
			name:     "no header uses DefaultTTL",
			headers:  http.Header{},
			expected: 60 * time.Second,
		},
		{
			name: "single valid max-age",
			headers: http.Header{
				"Cache-Control": []string{"max-age=120"},
			},
			expected: 120 * time.Second,
		},
		{
			name: "single valid max-age above MaxTTL is capped",
			headers: http.Header{
				"Cache-Control": []string{"max-age=600"},
			},
			expected: 300 * time.Second,
		},
		{
			name: "duplicate max-age chooses minimum (600 then 10)",
			headers: http.Header{
				"Cache-Control": []string{"max-age=600, max-age=10"},
			},
			expected: 10 * time.Second,
		},
		{
			name: "duplicate max-age chooses minimum (10 then 600) order-independent",
			headers: http.Header{
				"Cache-Control": []string{"max-age=10, max-age=600"},
			},
			expected: 10 * time.Second,
		},
		{
			name: "max-age=0 wins conservatively over larger max-age",
			headers: http.Header{
				"Cache-Control": []string{"max-age=0, max-age=600"},
			},
			expected: 0,
		},
		{
			name: "case-insensitive MAX-AGE=10",
			headers: http.Header{
				"Cache-Control": []string{"MAX-AGE=10"},
			},
			expected: 10 * time.Second,
		},
		{
			name: "multiple Cache-Control header fields choose minimum",
			headers: http.Header{
				"Cache-Control": []string{"max-age=600", "max-age=10"},
			},
			expected: 10 * time.Second,
		},
		{
			name: "malformed conflict never extends beyond DefaultTTL (600 then bogus)",
			headers: http.Header{
				"Cache-Control": []string{"max-age=600, max-age=bogus"},
			},
			expected: 60 * time.Second,
		},
		{
			name: "malformed conflict order-independent (bogus then 600)",
			headers: http.Header{
				"Cache-Control": []string{"max-age=bogus, max-age=600"},
			},
			expected: 60 * time.Second,
		},
		{
			name: "malformed conflict with smaller valid value keeps smaller value",
			headers: http.Header{
				"Cache-Control": []string{"max-age=10, max-age=bogus"},
			},
			expected: 10 * time.Second,
		},
		{
			name: "malformed invalid integer uses DefaultTTL",
			headers: http.Header{
				"Cache-Control": []string{"max-age=invalid"},
			},
			expected: 60 * time.Second,
		},
		{
			name: "malformed negative integer uses DefaultTTL",
			headers: http.Header{
				"Cache-Control": []string{"max-age=-50"},
			},
			expected: 60 * time.Second,
		},
		{
			name: "malformed overflowing integer uses DefaultTTL",
			headers: http.Header{
				"Cache-Control": []string{"max-age=999999999999999999999999999999"},
			},
			expected: 60 * time.Second,
		},
		{
			name: "bare max-age without value uses DefaultTTL",
			headers: http.Header{
				"Cache-Control": []string{"max-age"},
			},
			expected: 60 * time.Second,
		},
		{
			name: "empty max-age= uses DefaultTTL",
			headers: http.Header{
				"Cache-Control": []string{"max-age="},
			},
			expected: 60 * time.Second,
		},
		{
			name: "directives without max-age uses DefaultTTL",
			headers: http.Header{
				"Cache-Control": []string{"public, must-revalidate, no-transform"},
			},
			expected: 60 * time.Second,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := parseCacheControlTTL(tc.headers, policy)
			if got != tc.expected {
				t.Fatalf("expected TTL %v, got %v", tc.expected, got)
			}
		})
	}
}
