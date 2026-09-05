package executiongrant

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/lestrrat-go/jwx/v3/jwa"
	"github.com/lestrrat-go/jwx/v3/jws"
)

// PublicKeyResolver abstracts key lookup by key ID (kid).
type PublicKeyResolver interface {
	ResolveExecutionGrantKey(ctx context.Context, keyID string) (*ecdsa.PublicKey, error)
}

// Verifier provides offline cryptographic verification of execution-grant tokens.
type Verifier struct {
	expectedIssuer   string
	expectedAudience string
	clock            Clock
	keyResolver      PublicKeyResolver
}

// NewVerifier creates a new Verifier with strict configuration validation.
func NewVerifier(
	expectedIssuer string,
	expectedAudience string,
	clock Clock,
	keyResolver PublicKeyResolver,
) (*Verifier, error) {
	if strings.TrimSpace(expectedIssuer) == "" {
		return nil, errors.New("expectedIssuer must not be empty or whitespace")
	}
	if strings.TrimSpace(expectedAudience) == "" {
		return nil, errors.New("expectedAudience must not be empty or whitespace")
	}
	if clock == nil {
		return nil, errors.New("clock must not be nil")
	}
	if keyResolver == nil {
		return nil, errors.New("keyResolver must not be nil")
	}

	return &Verifier{
		expectedIssuer:   expectedIssuer,
		expectedAudience: expectedAudience,
		clock:            clock,
		keyResolver:      keyResolver,
	}, nil
}

// Verify validates an untrusted compact execution-grant token offline.
// It enforces pinned ES256, exact typ, nonblank kid, P-256 key shape,
// cryptographic signature validity, expected issuer and audience,
// strict expiration (gatewayNow < exp), and all required standard and custom claims.
func (v *Verifier) Verify(ctx context.Context, compactToken string) (VerifiedExecutionGrant, error) {
	if len(strings.TrimSpace(compactToken)) == 0 {
		return VerifiedExecutionGrant{}, ErrMissingToken
	}

	// Validate compact token structure (must have exactly 3 segments)
	parts := strings.Split(compactToken, ".")
	if len(parts) != 3 {
		return VerifiedExecutionGrant{}, ErrMalformedToken
	}

	// Decode raw protected header and validate unique JSON members before parsing or trusting headers
	headerBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return VerifiedExecutionGrant{}, ErrMalformedToken
	}
	if err := validateUniqueJSONMembers(headerBytes, true); err != nil {
		return VerifiedExecutionGrant{}, ErrMalformedToken
	}

	// Parse compact JWS structure without trusting unverified claims
	msg, err := jws.Parse([]byte(compactToken), jws.WithCompact())
	if err != nil {
		return VerifiedExecutionGrant{}, ErrMalformedToken
	}

	signatures := msg.Signatures()
	if len(signatures) != 1 {
		return VerifiedExecutionGrant{}, ErrMalformedToken
	}

	sig := signatures[0]
	headers := sig.ProtectedHeaders()
	if headers == nil {
		return VerifiedExecutionGrant{}, ErrMalformedToken
	}

	// 1. Enforce pinned algorithm: ES256 only
	alg, ok := headers.Algorithm()
	if !ok || alg != jwa.ES256() {
		return VerifiedExecutionGrant{}, ErrUnsupportedAlgorithm
	}

	// 2. Enforce exact typ: proofmesh-execution-grant+jwt
	typ, ok := headers.Type()
	if !ok || typ != ExpectedGrantType {
		return VerifiedExecutionGrant{}, ErrInvalidType
	}

	// 3. Enforce present and non-blank kid
	kid, ok := headers.KeyID()
	if !ok || len(kid) == 0 || strings.TrimSpace(kid) == "" {
		return VerifiedExecutionGrant{}, ErrMissingKeyID
	}

	// 4. Resolve trusted public key by kid
	pubKey, err := v.keyResolver.ResolveExecutionGrantKey(ctx, kid)
	if err != nil {
		if errors.Is(err, ErrUnknownKey) {
			return VerifiedExecutionGrant{}, ErrUnknownKey
		}
		return VerifiedExecutionGrant{}, ErrKeyResolutionFailed
	}

	// 5. Enforce trusted key is valid ECDSA P-256
	if pubKey == nil {
		return VerifiedExecutionGrant{}, ErrInvalidVerificationKey
	}
	if pubKey.Curve != elliptic.P256() {
		return VerifiedExecutionGrant{}, ErrInvalidVerificationKey
	}
	if pubKey.X == nil || pubKey.Y == nil || !pubKey.Curve.IsOnCurve(pubKey.X, pubKey.Y) {
		return VerifiedExecutionGrant{}, ErrInvalidVerificationKey
	}

	// 6. Cryptographically verify ES256 signature
	payloadBytes, err := jws.Verify([]byte(compactToken), jws.WithKey(jwa.ES256(), pubKey))
	if err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidSignature
	}

	// Validate unique JSON members in verified payload before extracting claims
	if err := validateUniqueJSONMembers(payloadBytes, true); err != nil {
		return VerifiedExecutionGrant{}, ErrMalformedToken
	}

	// 7. Parse claims from verified payload
	var claimsMap map[string]json.RawMessage
	if err := json.Unmarshal(payloadBytes, &claimsMap); err != nil {
		return VerifiedExecutionGrant{}, ErrMalformedToken
	}

	// Validate standard claims
	iss, err := extractStringClaim(claimsMap, "iss")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	if iss != v.expectedIssuer {
		return VerifiedExecutionGrant{}, ErrInvalidIssuer
	}

	aud, err := extractAudienceClaim(claimsMap, v.expectedAudience)
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}

	jtiStr, err := extractStringClaim(claimsMap, "jti")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	grantID, err := parseNonNilUUID(jtiStr)
	if err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidClaim
	}

	iat, err := extractNumericDateClaim(claimsMap, "iat")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}

	exp, err := extractNumericDateClaim(claimsMap, "exp")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}

	// Validity window check: exp > iat
	if !exp.After(iat) {
		return VerifiedExecutionGrant{}, ErrInvalidValidityWindow
	}

	// Strict expiration check: gatewayNow < exp (at gatewayNow == exp, reject)
	gatewayNow := v.clock.Now().UTC()
	if !gatewayNow.Before(exp) {
		return VerifiedExecutionGrant{}, ErrTokenExpired
	}

	// Validate custom claims
	orgIDStr, err := extractStringClaim(claimsMap, "org_id")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	orgID, err := parseNonNilUUID(orgIDStr)
	if err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidClaim
	}

	agentIDStr, err := extractStringClaim(claimsMap, "agent_id")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	agentID, err := parseNonNilUUID(agentIDStr)
	if err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidClaim
	}

	actionIDStr, err := extractStringClaim(claimsMap, "action_id")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	actionID, err := parseNonNilUUID(actionIDStr)
	if err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidClaim
	}

	decisionIDStr, err := extractStringClaim(claimsMap, "decision_id")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	decisionID, err := parseNonNilUUID(decisionIDStr)
	if err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidClaim
	}

	toolName, err := extractStringClaim(claimsMap, "tool_name")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	if err := validateNonBlankString(toolName); err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidClaim
	}

	operationName, err := extractStringClaim(claimsMap, "operation_name")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	if err := validateNonBlankString(operationName); err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidClaim
	}

	payloadHash, err := extractStringClaim(claimsMap, "payload_hash")
	if err != nil {
		return VerifiedExecutionGrant{}, err
	}
	if err := validatePayloadHash(payloadHash); err != nil {
		return VerifiedExecutionGrant{}, ErrInvalidClaim
	}

	return VerifiedExecutionGrant{
		GrantID:              grantID,
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decisionID,
		ToolName:             toolName,
		OperationName:        operationName,
		PayloadHash:          payloadHash,
		Issuer:               iss,
		Audience:             aud,
		IssuedAt:             iat,
		ExpiresAt:            exp,
		KeyID:                kid,
	}, nil
}

func extractStringClaim(claims map[string]json.RawMessage, key string) (string, error) {
	raw, ok := claims[key]
	if !ok {
		return "", ErrMissingClaim
	}
	if string(raw) == "null" {
		return "", ErrInvalidClaim
	}
	var val string
	if err := json.Unmarshal(raw, &val); err != nil {
		return "", ErrInvalidClaim
	}
	return val, nil
}

func extractAudienceClaim(claims map[string]json.RawMessage, expectedAudience string) (string, error) {
	raw, ok := claims["aud"]
	if !ok {
		return "", ErrMissingClaim
	}
	if string(raw) == "null" {
		return "", ErrInvalidClaim
	}

	// Try string representation first
	var singleAud string
	if err := json.Unmarshal(raw, &singleAud); err == nil {
		if singleAud != expectedAudience {
			return "", ErrInvalidAudience
		}
		return singleAud, nil
	}

	// Fallback to array representation with strict singleton semantics
	var audList []string
	if err := json.Unmarshal(raw, &audList); err == nil {
		if len(audList) != 1 || audList[0] != expectedAudience {
			return "", ErrInvalidAudience
		}
		return audList[0], nil
	}

	return "", ErrInvalidAudience
}

func extractNumericDateClaim(claims map[string]json.RawMessage, key string) (time.Time, error) {
	raw, ok := claims[key]
	if !ok {
		return time.Time{}, ErrMissingClaim
	}
	if string(raw) == "null" {
		return time.Time{}, ErrInvalidClaim
	}

	var sec int64
	if err := json.Unmarshal(raw, &sec); err != nil {
		return time.Time{}, ErrInvalidClaim
	}
	return time.Unix(sec, 0).UTC(), nil
}
