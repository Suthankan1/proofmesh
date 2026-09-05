package executiongrant

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/lestrrat-go/jwx/v3/jwa"
	"github.com/lestrrat-go/jwx/v3/jws"
)

type fixedClock struct {
	now time.Time
}

func (c fixedClock) Now() time.Time {
	return c.now
}

type testKeyResolver struct {
	keys map[string]*ecdsa.PublicKey
	err  error
}

func (r *testKeyResolver) ResolveExecutionGrantKey(ctx context.Context, kid string) (*ecdsa.PublicKey, error) {
	if r.err != nil {
		return nil, r.err
	}
	key, ok := r.keys[kid]
	if !ok {
		return nil, ErrUnknownKey
	}
	return key, nil
}

const (
	testIssuer   = "proofmesh-control-plane"
	testAudience = "proofmesh-gateway"
	testKeyID    = "key-test-01"
)

func generateP256Key(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate P-256 key: %v", err)
	}
	return key
}

func defaultClaims(now time.Time) map[string]any {
	iat := now.Unix()
	exp := now.Add(30 * time.Second).Unix()

	return map[string]any{
		"iss":            testIssuer,
		"aud":            testAudience,
		"jti":            uuid.New().String(),
		"iat":            iat,
		"exp":            exp,
		"org_id":         uuid.New().String(),
		"agent_id":       uuid.New().String(),
		"action_id":      uuid.New().String(),
		"decision_id":    uuid.New().String(),
		"tool_name":      "payments",
		"operation_name": "refund",
		"payload_hash":   "fe033f2b85fbcc6f563d9ecdcb7d04e40e3a8ef239f900db3b0b34bbe3e21ff7",
	}
}

func buildCompactToken(
	t *testing.T,
	privKey any,
	alg jwa.SignatureAlgorithm,
	typ string,
	kid string,
	claims map[string]any,
) string {
	t.Helper()
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatalf("failed to marshal claims: %v", err)
	}

	headers := jws.NewHeaders()
	if typ != "" {
		if err := headers.Set("typ", typ); err != nil {
			t.Fatalf("failed to set typ header: %v", err)
		}
	}
	if kid != "" {
		if err := headers.Set("kid", kid); err != nil {
			t.Fatalf("failed to set kid header: %v", err)
		}
	}

	signed, err := jws.Sign(payload, jws.WithKey(alg, privKey, jws.WithProtectedHeaders(headers)))
	if err != nil {
		t.Fatalf("failed to sign JWS: %v", err)
	}
	return string(signed)
}

func setupVerifier(t *testing.T, pubKey *ecdsa.PublicKey, fixedTime time.Time) (*Verifier, *testKeyResolver) {
	t.Helper()
	resolver := &testKeyResolver{
		keys: map[string]*ecdsa.PublicKey{
			testKeyID: pubKey,
		},
	}
	clock := fixedClock{now: fixedTime}
	v, err := NewVerifier(testIssuer, testAudience, clock, resolver)
	if err != nil {
		t.Fatalf("failed to create verifier: %v", err)
	}
	return v, resolver
}

func TestNewVerifier_ConfigurationValidation(t *testing.T) {
	clock := SystemClock{}
	resolver := &testKeyResolver{}

	tests := []struct {
		name     string
		iss      string
		aud      string
		clk      Clock
		res      PublicKeyResolver
		hasError bool
	}{
		{"valid", "iss", "aud", clock, resolver, false},
		{"blank_issuer", "", "aud", clock, resolver, true},
		{"whitespace_issuer", "   ", "aud", clock, resolver, true},
		{"blank_audience", "iss", "", clock, resolver, true},
		{"whitespace_audience", "iss", "   ", clock, resolver, true},
		{"nil_clock", "iss", "aud", nil, resolver, true},
		{"nil_resolver", "iss", "aud", clock, nil, true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := NewVerifier(tc.iss, tc.aud, tc.clk, tc.res)
			if tc.hasError && err == nil {
				t.Fatalf("expected error, got nil")
			}
			if !tc.hasError && err != nil {
				t.Fatalf("expected success, got error: %v", err)
			}
		})
	}
}

func TestVerifier_PositiveVerification(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, _ := setupVerifier(t, &privKey.PublicKey, now)

	claims := defaultClaims(now)
	token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)

	grant, err := verifier.Verify(context.Background(), token)
	if err != nil {
		t.Fatalf("expected successful verification, got: %v", err)
	}

	if grant.GrantID.String() != claims["jti"] {
		t.Errorf("GrantID mismatch: got %s, want %s", grant.GrantID, claims["jti"])
	}
	if grant.OrganizationID.String() != claims["org_id"] {
		t.Errorf("OrganizationID mismatch: got %s, want %s", grant.OrganizationID, claims["org_id"])
	}
	if grant.AgentID.String() != claims["agent_id"] {
		t.Errorf("AgentID mismatch: got %s, want %s", grant.AgentID, claims["agent_id"])
	}
	if grant.GovernedActionID.String() != claims["action_id"] {
		t.Errorf("GovernedActionID mismatch: got %s, want %s", grant.GovernedActionID, claims["action_id"])
	}
	if grant.GovernanceDecisionID.String() != claims["decision_id"] {
		t.Errorf("GovernanceDecisionID mismatch: got %s, want %s", grant.GovernanceDecisionID, claims["decision_id"])
	}
	if grant.ToolName != "payments" {
		t.Errorf("ToolName mismatch: got %s, want %s", grant.ToolName, "payments")
	}
	if grant.OperationName != "refund" {
		t.Errorf("OperationName mismatch: got %s, want %s", grant.OperationName, "refund")
	}
	if grant.PayloadHash != claims["payload_hash"] {
		t.Errorf("PayloadHash mismatch: got %s, want %s", grant.PayloadHash, claims["payload_hash"])
	}
	if grant.Issuer != testIssuer {
		t.Errorf("Issuer mismatch: got %s, want %s", grant.Issuer, testIssuer)
	}
	if grant.Audience != testAudience {
		t.Errorf("Audience mismatch: got %s, want %s", grant.Audience, testAudience)
	}
	if grant.KeyID != testKeyID {
		t.Errorf("KeyID mismatch: got %s, want %s", grant.KeyID, testKeyID)
	}
	if grant.IssuedAt.Unix() != claims["iat"].(int64) {
		t.Errorf("IssuedAt mismatch: got %v, want %v", grant.IssuedAt.Unix(), claims["iat"])
	}
	if grant.ExpiresAt.Unix() != claims["exp"].(int64) {
		t.Errorf("ExpiresAt mismatch: got %v, want %v", grant.ExpiresAt.Unix(), claims["exp"])
	}
}

func TestVerifier_AudienceSingletonArraySucceeds(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, _ := setupVerifier(t, &privKey.PublicKey, now)

	claims := defaultClaims(now)
	claims["aud"] = []string{testAudience}
	token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)

	grant, err := verifier.Verify(context.Background(), token)
	if err != nil {
		t.Fatalf("expected verification with singleton array aud to succeed, got: %v", err)
	}
	if grant.Audience != testAudience {
		t.Errorf("expected audience %q, got %q", testAudience, grant.Audience)
	}
}

func TestVerifier_UnknownExtraClaimsIgnored(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, _ := setupVerifier(t, &privKey.PublicKey, now)

	claims := defaultClaims(now)
	claims["custom_future_claim"] = "benign_data"
	claims["some_nested_field"] = map[string]any{"count": 42}
	token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)

	grant, err := verifier.Verify(context.Background(), token)
	if err != nil {
		t.Fatalf("expected token with extra claims to verify successfully, got: %v", err)
	}
	if grant.ToolName != "payments" {
		t.Errorf("expected toolName payments, got %s", grant.ToolName)
	}
}

func TestVerifier_HeaderNegativeTests(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, resolver := setupVerifier(t, &privKey.PublicKey, now)

	t.Run("blank_token", func(t *testing.T) {
		_, err := verifier.Verify(context.Background(), "")
		if !errors.Is(err, ErrMissingToken) {
			t.Fatalf("expected ErrMissingToken, got: %v", err)
		}
		_, err = verifier.Verify(context.Background(), "   \t\n  ")
		if !errors.Is(err, ErrMissingToken) {
			t.Fatalf("expected ErrMissingToken for whitespace, got: %v", err)
		}
	})

	t.Run("malformed_compact_token", func(t *testing.T) {
		malformedCases := []string{
			"onepart",
			"two.parts",
			"four.parts.here.extra",
			"not.base64.signature",
			"....",
		}
		for _, token := range malformedCases {
			_, err := verifier.Verify(context.Background(), token)
			if !errors.Is(err, ErrMalformedToken) {
				t.Fatalf("expected ErrMalformedToken for %q, got: %v", token, err)
			}
		}
	})

	t.Run("missing_typ", func(t *testing.T) {
		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), "", testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidType) {
			t.Fatalf("expected ErrInvalidType, got: %v", err)
		}
	})

	t.Run("wrong_typ", func(t *testing.T) {
		wrongTypes := []string{
			"JWT",
			"Bearer",
			"proofmesh-execution-grant",
			"PROOFMESH-EXECUTION-GRANT+JWT",
			" proofmesh-execution-grant+jwt ",
			"proofmesh-execution-grant+jwt-extra",
		}
		for _, wt := range wrongTypes {
			claims := defaultClaims(now)
			token := buildCompactToken(t, privKey, jwa.ES256(), wt, testKeyID, claims)
			_, err := verifier.Verify(context.Background(), token)
			if !errors.Is(err, ErrInvalidType) {
				t.Fatalf("expected ErrInvalidType for %q, got: %v", wt, err)
			}
		}
	})

	t.Run("missing_kid", func(t *testing.T) {
		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, "", claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrMissingKeyID) {
			t.Fatalf("expected ErrMissingKeyID, got: %v", err)
		}
	})

	t.Run("blank_kid", func(t *testing.T) {
		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, "   ", claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrMissingKeyID) {
			t.Fatalf("expected ErrMissingKeyID for blank kid, got: %v", err)
		}
	})

	t.Run("unknown_kid", func(t *testing.T) {
		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, "unknown-key-99", claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrUnknownKey) {
			t.Fatalf("expected ErrUnknownKey, got: %v", err)
		}
	})

	t.Run("wrong_p256_key", func(t *testing.T) {
		otherKey := generateP256Key(t)
		claims := defaultClaims(now)
		token := buildCompactToken(t, otherKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidSignature) {
			t.Fatalf("expected ErrInvalidSignature, got: %v", err)
		}
	})

	t.Run("tampered_payload", func(t *testing.T) {
		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		parts := strings.Split(token, ".")
		tamperedPayload := base64.RawURLEncoding.EncodeToString([]byte(`{"tool_name":"attacker"}`))
		tamperedToken := fmt.Sprintf("%s.%s.%s", parts[0], tamperedPayload, parts[2])

		_, err := verifier.Verify(context.Background(), tamperedToken)
		if !errors.Is(err, ErrInvalidSignature) {
			t.Fatalf("expected ErrInvalidSignature for tampered payload, got: %v", err)
		}
	})

	t.Run("resolver_returns_p384_key", func(t *testing.T) {
		p384Key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
		if err != nil {
			t.Fatalf("failed to generate P-384 key: %v", err)
		}
		resolver.keys["key-p384"] = &p384Key.PublicKey
		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, "key-p384", claims)

		_, err = verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidVerificationKey) {
			t.Fatalf("expected ErrInvalidVerificationKey for P-384 key, got: %v", err)
		}
	})

	t.Run("alg_none", func(t *testing.T) {
		hdrJSON := `{"alg":"none","typ":"proofmesh-execution-grant+jwt","kid":"key-test-01"}`
		claimsJSON, _ := json.Marshal(defaultClaims(now))
		token := base64.RawURLEncoding.EncodeToString([]byte(hdrJSON)) + "." +
			base64.RawURLEncoding.EncodeToString(claimsJSON) + "."

		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrUnsupportedAlgorithm) {
			t.Fatalf("expected ErrUnsupportedAlgorithm for alg=none, got: %v", err)
		}
	})

	t.Run("alg_hs256", func(t *testing.T) {
		claims := defaultClaims(now)
		token := buildCompactToken(t, []byte("super-secret-hmac-key-32-bytes!"), jwa.HS256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrUnsupportedAlgorithm) {
			t.Fatalf("expected ErrUnsupportedAlgorithm for HS256, got: %v", err)
		}
	})

	t.Run("alg_rs256", func(t *testing.T) {
		rsaKey, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			t.Fatalf("failed to generate RSA key: %v", err)
		}
		claims := defaultClaims(now)
		token := buildCompactToken(t, rsaKey, jwa.RS256(), ExpectedGrantType, testKeyID, claims)
		_, err = verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrUnsupportedAlgorithm) {
			t.Fatalf("expected ErrUnsupportedAlgorithm for RS256, got: %v", err)
		}
	})

	t.Run("alg_es384", func(t *testing.T) {
		p384Key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
		if err != nil {
			t.Fatalf("failed to generate P-384 key: %v", err)
		}
		claims := defaultClaims(now)
		token := buildCompactToken(t, p384Key, jwa.ES384(), ExpectedGrantType, testKeyID, claims)
		_, err = verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrUnsupportedAlgorithm) {
			t.Fatalf("expected ErrUnsupportedAlgorithm for ES384, got: %v", err)
		}
	})
}

func TestVerifier_StandardClaimNegativeTests(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, _ := setupVerifier(t, &privKey.PublicKey, now)

	type testCase struct {
		name        string
		mutator     func(c map[string]any)
		expectedErr error
	}

	tests := []testCase{
		{
			name:        "missing_iss",
			mutator:     func(c map[string]any) { delete(c, "iss") },
			expectedErr: ErrMissingClaim,
		},
		{
			name:        "null_iss",
			mutator:     func(c map[string]any) { c["iss"] = nil },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "wrong_iss",
			mutator:     func(c map[string]any) { c["iss"] = "attacker-control-plane" },
			expectedErr: ErrInvalidIssuer,
		},
		{
			name:        "non_string_iss",
			mutator:     func(c map[string]any) { c["iss"] = 12345 },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "missing_aud",
			mutator:     func(c map[string]any) { delete(c, "aud") },
			expectedErr: ErrMissingClaim,
		},
		{
			name:        "null_aud",
			mutator:     func(c map[string]any) { c["aud"] = nil },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "wrong_aud",
			mutator:     func(c map[string]any) { c["aud"] = "attacker-gateway" },
			expectedErr: ErrInvalidAudience,
		},
		{
			name:        "empty_array_aud",
			mutator:     func(c map[string]any) { c["aud"] = []string{} },
			expectedErr: ErrInvalidAudience,
		},
		{
			name:        "multiple_aud_array",
			mutator:     func(c map[string]any) { c["aud"] = []string{testAudience, "other-audience"} },
			expectedErr: ErrInvalidAudience,
		},
		{
			name:        "non_string_aud",
			mutator:     func(c map[string]any) { c["aud"] = 42 },
			expectedErr: ErrInvalidAudience,
		},
		{
			name:        "missing_jti",
			mutator:     func(c map[string]any) { delete(c, "jti") },
			expectedErr: ErrMissingClaim,
		},
		{
			name:        "null_jti",
			mutator:     func(c map[string]any) { c["jti"] = nil },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "invalid_uuid_jti",
			mutator:     func(c map[string]any) { c["jti"] = "not-a-valid-uuid" },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "nil_uuid_jti",
			mutator:     func(c map[string]any) { c["jti"] = uuid.Nil.String() },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "missing_iat",
			mutator:     func(c map[string]any) { delete(c, "iat") },
			expectedErr: ErrMissingClaim,
		},
		{
			name:        "null_iat",
			mutator:     func(c map[string]any) { c["iat"] = nil },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "non_numeric_iat",
			mutator:     func(c map[string]any) { c["iat"] = "2026-09-05T12:00:00Z" },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "missing_exp",
			mutator:     func(c map[string]any) { delete(c, "exp") },
			expectedErr: ErrMissingClaim,
		},
		{
			name:        "null_exp",
			mutator:     func(c map[string]any) { c["exp"] = nil },
			expectedErr: ErrInvalidClaim,
		},
		{
			name:        "non_numeric_exp",
			mutator:     func(c map[string]any) { c["exp"] = "2026-09-05T12:00:30Z" },
			expectedErr: ErrInvalidClaim,
		},
		{
			name: "exp_equals_iat",
			mutator: func(c map[string]any) {
				c["exp"] = c["iat"]
			},
			expectedErr: ErrInvalidValidityWindow,
		},
		{
			name: "exp_less_than_iat",
			mutator: func(c map[string]any) {
				c["exp"] = c["iat"].(int64) - 5
			},
			expectedErr: ErrInvalidValidityWindow,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			claims := defaultClaims(now)
			tc.mutator(claims)
			token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)

			_, err := verifier.Verify(context.Background(), token)
			if !errors.Is(err, tc.expectedErr) {
				t.Fatalf("expected error %v, got %v", tc.expectedErr, err)
			}
		})
	}
}

func TestVerifier_ExpirationBoundaryStrictness(t *testing.T) {
	privKey := generateP256Key(t)
	iatSec := int64(1725530400)
	expSec := int64(1725530430)

	claims := defaultClaims(time.Unix(iatSec, 0).UTC())
	claims["iat"] = iatSec
	claims["exp"] = expSec
	token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)

	// Case 1: now < exp by 1 second (1725530429) -> MUST succeed
	{
		clock := fixedClock{now: time.Unix(expSec-1, 0).UTC()}
		resolver := &testKeyResolver{keys: map[string]*ecdsa.PublicKey{testKeyID: &privKey.PublicKey}}
		v, err := NewVerifier(testIssuer, testAudience, clock, resolver)
		if err != nil {
			t.Fatalf("failed to create verifier: %v", err)
		}
		_, err = v.Verify(context.Background(), token)
		if err != nil {
			t.Fatalf("expected success at now < exp, got: %v", err)
		}
	}

	// Case 2: now == exp (1725530430) -> MUST fail with ErrTokenExpired (no positive leeway!)
	{
		clock := fixedClock{now: time.Unix(expSec, 0).UTC()}
		resolver := &testKeyResolver{keys: map[string]*ecdsa.PublicKey{testKeyID: &privKey.PublicKey}}
		v, err := NewVerifier(testIssuer, testAudience, clock, resolver)
		if err != nil {
			t.Fatalf("failed to create verifier: %v", err)
		}
		_, err = v.Verify(context.Background(), token)
		if !errors.Is(err, ErrTokenExpired) {
			t.Fatalf("expected ErrTokenExpired at now == exp, got: %v", err)
		}
	}

	// Case 3: now > exp (1725530431) -> MUST fail with ErrTokenExpired
	{
		clock := fixedClock{now: time.Unix(expSec+1, 0).UTC()}
		resolver := &testKeyResolver{keys: map[string]*ecdsa.PublicKey{testKeyID: &privKey.PublicKey}}
		v, err := NewVerifier(testIssuer, testAudience, clock, resolver)
		if err != nil {
			t.Fatalf("failed to create verifier: %v", err)
		}
		_, err = v.Verify(context.Background(), token)
		if !errors.Is(err, ErrTokenExpired) {
			t.Fatalf("expected ErrTokenExpired at now > exp, got: %v", err)
		}
	}
}

func TestVerifier_CustomClaimNegativeTests(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, _ := setupVerifier(t, &privKey.PublicKey, now)

	requiredCustomClaims := []string{
		"org_id",
		"agent_id",
		"action_id",
		"decision_id",
		"tool_name",
		"operation_name",
		"payload_hash",
	}

	for _, claim := range requiredCustomClaims {
		t.Run("missing_"+claim, func(t *testing.T) {
			claims := defaultClaims(now)
			delete(claims, claim)
			token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
			_, err := verifier.Verify(context.Background(), token)
			if !errors.Is(err, ErrMissingClaim) {
				t.Fatalf("expected ErrMissingClaim, got: %v", err)
			}
		})

		t.Run("null_"+claim, func(t *testing.T) {
			claims := defaultClaims(now)
			claims[claim] = nil
			token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
			_, err := verifier.Verify(context.Background(), token)
			if !errors.Is(err, ErrInvalidClaim) {
				t.Fatalf("expected ErrInvalidClaim for null %s, got: %v", claim, err)
			}
		})

		t.Run("non_string_"+claim, func(t *testing.T) {
			claims := defaultClaims(now)
			claims[claim] = 12345
			token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
			_, err := verifier.Verify(context.Background(), token)
			if !errors.Is(err, ErrInvalidClaim) {
				t.Fatalf("expected ErrInvalidClaim for non-string %s, got: %v", claim, err)
			}
		})
	}

	uuidClaims := []string{"org_id", "agent_id", "action_id", "decision_id"}
	for _, claim := range uuidClaims {
		t.Run("invalid_uuid_"+claim, func(t *testing.T) {
			claims := defaultClaims(now)
			claims[claim] = "not-a-valid-uuid"
			token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
			_, err := verifier.Verify(context.Background(), token)
			if !errors.Is(err, ErrInvalidClaim) {
				t.Fatalf("expected ErrInvalidClaim for invalid uuid %s, got: %v", claim, err)
			}
		})

		t.Run("nil_uuid_"+claim, func(t *testing.T) {
			claims := defaultClaims(now)
			claims[claim] = uuid.Nil.String()
			token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
			_, err := verifier.Verify(context.Background(), token)
			if !errors.Is(err, ErrInvalidClaim) {
				t.Fatalf("expected ErrInvalidClaim for nil uuid %s, got: %v", claim, err)
			}
		})
	}

	t.Run("blank_tool_name", func(t *testing.T) {
		claims := defaultClaims(now)
		claims["tool_name"] = "   "
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidClaim) {
			t.Fatalf("expected ErrInvalidClaim for blank tool_name, got: %v", err)
		}
	})

	t.Run("empty_tool_name", func(t *testing.T) {
		claims := defaultClaims(now)
		claims["tool_name"] = ""
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidClaim) {
			t.Fatalf("expected ErrInvalidClaim for empty tool_name, got: %v", err)
		}
	})

	t.Run("blank_operation_name", func(t *testing.T) {
		claims := defaultClaims(now)
		claims["operation_name"] = "   "
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidClaim) {
			t.Fatalf("expected ErrInvalidClaim for blank operation_name, got: %v", err)
		}
	})

	t.Run("payload_hash_uppercase", func(t *testing.T) {
		claims := defaultClaims(now)
		claims["payload_hash"] = strings.ToUpper(claims["payload_hash"].(string))
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidClaim) {
			t.Fatalf("expected ErrInvalidClaim for uppercase payload_hash, got: %v", err)
		}
	})

	t.Run("payload_hash_length_63", func(t *testing.T) {
		claims := defaultClaims(now)
		claims["payload_hash"] = claims["payload_hash"].(string)[:63]
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidClaim) {
			t.Fatalf("expected ErrInvalidClaim for length 63 payload_hash, got: %v", err)
		}
	})

	t.Run("payload_hash_length_65", func(t *testing.T) {
		claims := defaultClaims(now)
		claims["payload_hash"] = claims["payload_hash"].(string) + "a"
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidClaim) {
			t.Fatalf("expected ErrInvalidClaim for length 65 payload_hash, got: %v", err)
		}
	})

	t.Run("payload_hash_nonhex", func(t *testing.T) {
		claims := defaultClaims(now)
		claims["payload_hash"] = strings.Replace(claims["payload_hash"].(string), "a", "z", 1)
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidClaim) {
			t.Fatalf("expected ErrInvalidClaim for nonhex payload_hash, got: %v", err)
		}
	})

	t.Run("payload_hash_whitespace", func(t *testing.T) {
		claims := defaultClaims(now)
		claims["payload_hash"] = " " + claims["payload_hash"].(string)[1:]
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)
		_, err := verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidClaim) {
			t.Fatalf("expected ErrInvalidClaim for whitespace payload_hash, got: %v", err)
		}
	})
}

func TestVerifier_ResolverBehavior(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)

	t.Run("resolver_internal_failure", func(t *testing.T) {
		clock := fixedClock{now: now}
		resolver := &testKeyResolver{
			err: errors.New("database connection refused"),
		}
		verifier, err := NewVerifier(testIssuer, testAudience, clock, resolver)
		if err != nil {
			t.Fatalf("failed to create verifier: %v", err)
		}

		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)

		_, err = verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrKeyResolutionFailed) {
			t.Fatalf("expected ErrKeyResolutionFailed, got: %v", err)
		}
	})

	t.Run("resolver_returns_nil_key", func(t *testing.T) {
		clock := fixedClock{now: now}
		resolver := &testKeyResolver{
			keys: map[string]*ecdsa.PublicKey{
				testKeyID: nil,
			},
		}
		verifier, err := NewVerifier(testIssuer, testAudience, clock, resolver)
		if err != nil {
			t.Fatalf("failed to create verifier: %v", err)
		}

		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)

		_, err = verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidVerificationKey) {
			t.Fatalf("expected ErrInvalidVerificationKey, got: %v", err)
		}
	})

	t.Run("resolver_not_called_on_invalid_header", func(t *testing.T) {
		resolverCalled := false
		resolver := &mockResolverFn{
			fn: func(ctx context.Context, kid string) (*ecdsa.PublicKey, error) {
				resolverCalled = true
				return &privKey.PublicKey, nil
			},
		}
		verifier, err := NewVerifier(testIssuer, testAudience, fixedClock{now: now}, resolver)
		if err != nil {
			t.Fatalf("failed to create verifier: %v", err)
		}

		// Invalid header: wrong typ
		claims := defaultClaims(now)
		token := buildCompactToken(t, privKey, jwa.ES256(), "wrong-typ", testKeyID, claims)
		_, err = verifier.Verify(context.Background(), token)
		if !errors.Is(err, ErrInvalidType) {
			t.Fatalf("expected ErrInvalidType, got: %v", err)
		}
		if resolverCalled {
			t.Fatalf("resolver should not be called when header typ is invalid")
		}
	})
}

type mockResolverFn struct {
	fn func(ctx context.Context, kid string) (*ecdsa.PublicKey, error)
}

func (m *mockResolverFn) ResolveExecutionGrantKey(ctx context.Context, kid string) (*ecdsa.PublicKey, error) {
	return m.fn(ctx, kid)
}

func TestVerifier_ErrorRedaction(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, _ := setupVerifier(t, &privKey.PublicKey, now)

	canarySecret := "CANARY_SECRET_TOKEN_VALUE_XYZ_98765"

	claims := defaultClaims(now)
	claims["tool_name"] = canarySecret + "_tool"
	token := buildCompactToken(t, privKey, jwa.ES256(), ExpectedGrantType, testKeyID, claims)

	// Induce signature failure
	parts := strings.Split(token, ".")
	tamperedToken := fmt.Sprintf("%s.%s.%s", parts[0], parts[1], "corrupted_sig")

	_, err := verifier.Verify(context.Background(), tamperedToken)
	if err == nil {
		t.Fatalf("expected error")
	}

	errMsg := err.Error()
	if strings.Contains(errMsg, canarySecret) {
		t.Fatalf("error message leaked canary secret: %s", errMsg)
	}
	if strings.Contains(errMsg, tamperedToken) {
		t.Fatalf("error message leaked compact token: %s", errMsg)
	}
}

func TestVerifier_DuplicateJOSEHeaderMembers(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)

	tests := []struct {
		name       string
		headerJSON string
	}{
		{
			name:       "duplicate_alg",
			headerJSON: `{"alg":"ES256","alg":"HS256","typ":"proofmesh-execution-grant+jwt","kid":"key-test-01"}`,
		},
		{
			name:       "duplicate_typ",
			headerJSON: `{"alg":"ES256","typ":"proofmesh-execution-grant+jwt","typ":"JWT","kid":"key-test-01"}`,
		},
		{
			name:       "duplicate_kid",
			headerJSON: `{"alg":"ES256","typ":"proofmesh-execution-grant+jwt","kid":"key-test-01","kid":"other-key"}`,
		},
		{
			name:       "duplicate_extra_header",
			headerJSON: `{"alg":"ES256","typ":"proofmesh-execution-grant+jwt","kid":"key-test-01","extra":"a","extra":"b"}`,
		},
		{
			name:       "duplicate_nested_header",
			headerJSON: `{"alg":"ES256","typ":"proofmesh-execution-grant+jwt","kid":"key-test-01","nested":{"k":1,"k":2}}`,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			resolverCalled := false
			resolver := &mockResolverFn{
				fn: func(ctx context.Context, kid string) (*ecdsa.PublicKey, error) {
					resolverCalled = true
					return &privKey.PublicKey, nil
				},
			}
			verifier, err := NewVerifier(testIssuer, testAudience, fixedClock{now: now}, resolver)
			if err != nil {
				t.Fatalf("failed to create verifier: %v", err)
			}

			// Assemble compact token with duplicate header JSON
			claims := defaultClaims(now)
			payloadBytes, err := json.Marshal(claims)
			if err != nil {
				t.Fatalf("failed to marshal claims: %v", err)
			}

			hB64 := base64.RawURLEncoding.EncodeToString([]byte(tc.headerJSON))
			pB64 := base64.RawURLEncoding.EncodeToString(payloadBytes)
			token := fmt.Sprintf("%s.%s.dummy_sig_bytes", hB64, pB64)

			_, err = verifier.Verify(context.Background(), token)
			if !errors.Is(err, ErrMalformedToken) {
				t.Fatalf("expected ErrMalformedToken for duplicate header, got: %v", err)
			}
			if resolverCalled {
				t.Fatalf("key resolver must NOT be called when protected header contains duplicate members")
			}
		})
	}
}

func TestVerifier_DuplicateJWTClaims(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, _ := setupVerifier(t, &privKey.PublicKey, now)

	baseClaims := defaultClaims(now)

	tests := []struct {
		name        string
		payloadJSON string
	}{
		{
			name: "duplicate_iss",
			payloadJSON: fmt.Sprintf(`{
				"iss": "%s",
				"iss": "rogue-issuer",
				"aud": "%s",
				"jti": "%s",
				"iat": %d,
				"exp": %d,
				"org_id": "%s",
				"agent_id": "%s",
				"action_id": "%s",
				"decision_id": "%s",
				"tool_name": "payments",
				"operation_name": "refund",
				"payload_hash": "%s"
			}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
				baseClaims["org_id"], baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"]),
		},
		{
			name: "duplicate_aud",
			payloadJSON: fmt.Sprintf(`{
				"iss": "%s",
				"aud": "%s",
				"aud": "rogue-audience",
				"jti": "%s",
				"iat": %d,
				"exp": %d,
				"org_id": "%s",
				"agent_id": "%s",
				"action_id": "%s",
				"decision_id": "%s",
				"tool_name": "payments",
				"operation_name": "refund",
				"payload_hash": "%s"
			}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
				baseClaims["org_id"], baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"]),
		},
		{
			name: "duplicate_exp",
			payloadJSON: fmt.Sprintf(`{
				"iss": "%s",
				"aud": "%s",
				"jti": "%s",
				"iat": %d,
				"exp": %d,
				"exp": %d,
				"org_id": "%s",
				"agent_id": "%s",
				"action_id": "%s",
				"decision_id": "%s",
				"tool_name": "payments",
				"operation_name": "refund",
				"payload_hash": "%s"
			}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
				now.Add(300*time.Second).Unix(),
				baseClaims["org_id"], baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"]),
		},
		{
			name: "duplicate_org_id",
			payloadJSON: fmt.Sprintf(`{
				"iss": "%s",
				"aud": "%s",
				"jti": "%s",
				"iat": %d,
				"exp": %d,
				"org_id": "%s",
				"org_id": "%s",
				"agent_id": "%s",
				"action_id": "%s",
				"decision_id": "%s",
				"tool_name": "payments",
				"operation_name": "refund",
				"payload_hash": "%s"
			}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
				baseClaims["org_id"], uuid.New().String(),
				baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"]),
		},
		{
			name: "duplicate_payload_hash",
			payloadJSON: fmt.Sprintf(`{
				"iss": "%s",
				"aud": "%s",
				"jti": "%s",
				"iat": %d,
				"exp": %d,
				"org_id": "%s",
				"agent_id": "%s",
				"action_id": "%s",
				"decision_id": "%s",
				"tool_name": "payments",
				"operation_name": "refund",
				"payload_hash": "%s",
				"payload_hash": "0000000000000000000000000000000000000000000000000000000000000000"
			}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
				baseClaims["org_id"], baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"]),
		},
		{
			name: "duplicate_tool_name",
			payloadJSON: fmt.Sprintf(`{
				"iss": "%s",
				"aud": "%s",
				"jti": "%s",
				"iat": %d,
				"exp": %d,
				"org_id": "%s",
				"agent_id": "%s",
				"action_id": "%s",
				"decision_id": "%s",
				"tool_name": "payments",
				"tool_name": "rogue_tool",
				"operation_name": "refund",
				"payload_hash": "%s"
			}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
				baseClaims["org_id"], baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"]),
		},
		{
			name: "duplicate_operation_name",
			payloadJSON: fmt.Sprintf(`{
				"iss": "%s",
				"aud": "%s",
				"jti": "%s",
				"iat": %d,
				"exp": %d,
				"org_id": "%s",
				"agent_id": "%s",
				"action_id": "%s",
				"decision_id": "%s",
				"tool_name": "payments",
				"operation_name": "refund",
				"operation_name": "wipe_db",
				"payload_hash": "%s"
			}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
				baseClaims["org_id"], baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"]),
		},
		{
			name: "duplicate_nested_claim",
			payloadJSON: fmt.Sprintf(`{
				"iss": "%s",
				"aud": "%s",
				"jti": "%s",
				"iat": %d,
				"exp": %d,
				"org_id": "%s",
				"agent_id": "%s",
				"action_id": "%s",
				"decision_id": "%s",
				"tool_name": "payments",
				"operation_name": "refund",
				"payload_hash": "%s",
				"custom_data": {"dup": 1, "dup": 2}
			}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
				baseClaims["org_id"], baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"]),
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			// Sign the raw duplicate-containing payload with genuine ES256
			headers := jws.NewHeaders()
			if err := headers.Set("typ", ExpectedGrantType); err != nil {
				t.Fatalf("failed to set typ: %v", err)
			}
			if err := headers.Set("kid", testKeyID); err != nil {
				t.Fatalf("failed to set kid: %v", err)
			}

			signedTokenBytes, err := jws.Sign(
				[]byte(tc.payloadJSON),
				jws.WithKey(jwa.ES256(), privKey, jws.WithProtectedHeaders(headers)),
			)
			if err != nil {
				t.Fatalf("failed to sign token: %v", err)
			}

			// Signature is cryptographically valid, but duplicate payload claims must trigger ErrMalformedToken
			_, err = verifier.Verify(context.Background(), string(signedTokenBytes))
			if !errors.Is(err, ErrMalformedToken) {
				t.Fatalf("expected ErrMalformedToken for payload with duplicate claims, got: %v", err)
			}
		})
	}
}

func TestVerifier_DuplicateJSON_ErrorRedaction(t *testing.T) {
	privKey := generateP256Key(t)
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	verifier, _ := setupVerifier(t, &privKey.PublicKey, now)

	const canaryHeaderSecret = "SUPER_SECRET_DUPLICATE_HEADER_CANARY_DO_NOT_LEAK"
	const canaryPayloadSecret = "SUPER_SECRET_DUPLICATE_PAYLOAD_CANARY_DO_NOT_LEAK"

	t.Run("header_duplicate_error_redaction", func(t *testing.T) {
		dupHeader := fmt.Sprintf(`{"alg":"ES256","typ":"proofmesh-execution-grant+jwt","kid":"key-test-01","%s":"v1","%s":"v2"}`,
			canaryHeaderSecret, canaryHeaderSecret)

		hB64 := base64.RawURLEncoding.EncodeToString([]byte(dupHeader))
		pB64 := base64.RawURLEncoding.EncodeToString([]byte(`{}`))
		token := fmt.Sprintf("%s.%s.dummy_sig", hB64, pB64)

		_, err := verifier.Verify(context.Background(), token)
		if err == nil {
			t.Fatal("expected error, got nil")
		}

		for _, str := range []string{
			err.Error(),
			fmt.Sprintf("%v", err),
			fmt.Sprintf("%+v", err),
		} {
			if strings.Contains(str, canaryHeaderSecret) {
				t.Errorf("error leaked canary header secret: %s", str)
			}
		}
	})

	t.Run("payload_duplicate_error_redaction", func(t *testing.T) {
		baseClaims := defaultClaims(now)
		dupPayload := fmt.Sprintf(`{
			"iss": "%s",
			"aud": "%s",
			"jti": "%s",
			"iat": %d,
			"exp": %d,
			"org_id": "%s",
			"agent_id": "%s",
			"action_id": "%s",
			"decision_id": "%s",
			"tool_name": "payments",
			"operation_name": "refund",
			"payload_hash": "%s",
			"%s": "secret1",
			"%s": "secret2"
		}`, baseClaims["iss"], baseClaims["aud"], baseClaims["jti"], baseClaims["iat"], baseClaims["exp"],
			baseClaims["org_id"], baseClaims["agent_id"], baseClaims["action_id"], baseClaims["decision_id"], baseClaims["payload_hash"],
			canaryPayloadSecret, canaryPayloadSecret)

		headers := jws.NewHeaders()
		if err := headers.Set("typ", ExpectedGrantType); err != nil {
			t.Fatalf("failed to set typ: %v", err)
		}
		if err := headers.Set("kid", testKeyID); err != nil {
			t.Fatalf("failed to set kid: %v", err)
		}

		signedToken, err := jws.Sign(
			[]byte(dupPayload),
			jws.WithKey(jwa.ES256(), privKey, jws.WithProtectedHeaders(headers)),
		)
		if err != nil {
			t.Fatalf("failed to sign token: %v", err)
		}

		_, err = verifier.Verify(context.Background(), string(signedToken))
		if err == nil {
			t.Fatal("expected error, got nil")
		}

		for _, str := range []string{
			err.Error(),
			fmt.Sprintf("%v", err),
			fmt.Sprintf("%+v", err),
		} {
			if strings.Contains(str, canaryPayloadSecret) {
				t.Errorf("error leaked canary payload secret: %s", str)
			}
		}
	})
}
