package executiongrant

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"encoding/json"
	"errors"
	"io"
	"math"
	"math/big"
	"mime"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/lestrrat-go/jwx/v3/jwk"
)

// JWKSResolver resolves public keys from a trusted JWKS HTTP endpoint
// with bounded caching, serialized refreshes, and refresh cooldown.
type JWKSResolver struct {
	endpointURL string
	clock       Clock
	httpClient  *http.Client
	policy      JWKSResolverPolicy

	refreshMu sync.Mutex

	mu                   sync.RWMutex
	keys                 map[string]*ecdsa.PublicKey
	expiresAt            time.Time
	nextRefreshAllowedAt time.Time
}

// NewJWKSResolver constructs a validated JWKSResolver.
func NewJWKSResolver(
	endpointURL string,
	clock Clock,
	transport http.RoundTripper,
	policy JWKSResolverPolicy,
) (*JWKSResolver, error) {
	if strings.TrimSpace(endpointURL) == "" {
		return nil, errors.New("endpoint URL must not be empty or whitespace")
	}

	parsedURL, err := url.Parse(endpointURL)
	if err != nil {
		return nil, errors.New("endpoint URL is invalid")
	}

	if !parsedURL.IsAbs() {
		return nil, errors.New("endpoint URL must be absolute")
	}

	if parsedURL.Scheme != "http" && parsedURL.Scheme != "https" {
		return nil, errors.New("endpoint URL scheme must be http or https")
	}

	if strings.TrimSpace(parsedURL.Host) == "" {
		return nil, errors.New("endpoint URL host must not be empty")
	}

	if parsedURL.User != nil {
		return nil, errors.New("endpoint URL must not contain userinfo")
	}

	if parsedURL.Fragment != "" {
		return nil, errors.New("endpoint URL must not contain fragment")
	}

	if clock == nil {
		return nil, errors.New("clock must not be nil")
	}

	if err := policy.Validate(); err != nil {
		return nil, err
	}

	if transport == nil {
		transport = http.DefaultTransport
	}

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	return &JWKSResolver{
		endpointURL: endpointURL,
		clock:       clock,
		httpClient:  client,
		policy:      policy,
		keys:        make(map[string]*ecdsa.PublicKey),
	}, nil
}

// ResolveExecutionGrantKey resolves an ECDSA P-256 public key by exact key ID.
// It implements PublicKeyResolver.
func (r *JWKSResolver) ResolveExecutionGrantKey(ctx context.Context, keyID string) (*ecdsa.PublicKey, error) {
	if len(keyID) == 0 || strings.TrimSpace(keyID) == "" {
		return nil, ErrUnknownKey
	}

	// Fast path: cache is fresh and key is known
	r.mu.RLock()
	now := r.clock.Now().UTC()
	if !r.expiresAt.IsZero() && now.Before(r.expiresAt) {
		if key, ok := r.keys[keyID]; ok {
			cp := copyECDSAPublicKey(key)
			r.mu.RUnlock()
			return cp, nil
		}
	}
	r.mu.RUnlock()

	// Context check before waiting on refresh lock
	if err := ctx.Err(); err != nil {
		return nil, ErrKeyResolutionFailed
	}

	r.refreshMu.Lock()
	defer r.refreshMu.Unlock()

	// Context check after acquiring refresh lock
	if err := ctx.Err(); err != nil {
		return nil, ErrKeyResolutionFailed
	}

	// Re-check state under lock: another caller may have refreshed already
	r.mu.RLock()
	now = r.clock.Now().UTC()
	isFresh := !r.expiresAt.IsZero() && now.Before(r.expiresAt)
	if isFresh {
		if key, ok := r.keys[keyID]; ok {
			cp := copyECDSAPublicKey(key)
			r.mu.RUnlock()
			return cp, nil
		}
	}
	suppressed := now.Before(r.nextRefreshAllowedAt)
	r.mu.RUnlock()

	if suppressed {
		if isFresh {
			return nil, ErrUnknownKey
		}
		return nil, ErrKeyResolutionFailed
	}

	// Perform serialized HTTP fetch
	newKeys, ttl, fetchErr := r.fetchJWKS(ctx)

	now = r.clock.Now().UTC()
	r.mu.Lock()
	if fetchErr != nil || isFresh {
		r.nextRefreshAllowedAt = now.Add(r.policy.RefreshCooldown)
	}
	if fetchErr != nil {
		r.mu.Unlock()
		return nil, ErrKeyResolutionFailed
	}

	// Atomically replace cached key state
	r.keys = newKeys
	r.expiresAt = now.Add(ttl)
	key, ok := r.keys[keyID]
	var cp *ecdsa.PublicKey
	if ok {
		cp = copyECDSAPublicKey(key)
	}
	r.mu.Unlock()

	if !ok {
		return nil, ErrUnknownKey
	}
	return cp, nil
}

func (r *JWKSResolver) fetchJWKS(ctx context.Context) (map[string]*ecdsa.PublicKey, time.Duration, error) {
	reqCtx, cancel := context.WithTimeout(ctx, r.policy.RequestTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, r.endpointURL, nil)
	if err != nil {
		return nil, 0, ErrKeyResolutionFailed
	}
	req.Header.Set("Accept", "application/jwk-set+json, application/json")

	resp, err := r.httpClient.Do(req)
	if err != nil {
		return nil, 0, ErrKeyResolutionFailed
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, 0, ErrKeyResolutionFailed
	}

	ct := resp.Header.Get("Content-Type")
	if strings.TrimSpace(ct) == "" {
		return nil, 0, ErrKeyResolutionFailed
	}
	mediaType, _, err := mime.ParseMediaType(ct)
	if err != nil {
		return nil, 0, ErrKeyResolutionFailed
	}
	mediaType = strings.ToLower(mediaType)
	if mediaType != "application/json" && mediaType != "application/jwk-set+json" {
		return nil, 0, ErrKeyResolutionFailed
	}

	limitReader := io.LimitReader(resp.Body, r.policy.MaxResponseBytes+1)
	bodyBytes, err := io.ReadAll(limitReader)
	if err != nil {
		return nil, 0, ErrKeyResolutionFailed
	}
	if int64(len(bodyBytes)) > r.policy.MaxResponseBytes {
		return nil, 0, ErrKeyResolutionFailed
	}

	// Reject duplicate JSON members and non-object root
	if err := validateUniqueJSONMembers(bodyBytes, true); err != nil {
		return nil, 0, ErrKeyResolutionFailed
	}

	keys, err := parseAndValidateJWKS(bodyBytes)
	if err != nil {
		return nil, 0, ErrKeyResolutionFailed
	}

	ttl := parseCacheControlTTL(resp.Header, r.policy)
	return keys, ttl, nil
}

func parseAndValidateJWKS(data []byte) (map[string]*ecdsa.PublicKey, error) {
	var rootMap map[string]json.RawMessage
	if err := json.Unmarshal(data, &rootMap); err != nil {
		return nil, ErrKeyResolutionFailed
	}

	rawKeys, hasKeys := rootMap["keys"]
	if !hasKeys {
		return nil, ErrKeyResolutionFailed
	}

	trimmedKeys := bytes.TrimSpace(rawKeys)
	if len(trimmedKeys) == 0 || trimmedKeys[0] != '[' {
		return nil, ErrKeyResolutionFailed
	}

	var keyList []json.RawMessage
	if err := json.Unmarshal(rawKeys, &keyList); err != nil {
		return nil, ErrKeyResolutionFailed
	}

	if len(keyList) == 0 {
		return nil, ErrKeyResolutionFailed
	}

	tempKeys := make(map[string]*ecdsa.PublicKey, len(keyList))
	seenKIDs := make(map[string]struct{}, len(keyList))

	for _, entry := range keyList {
		trimmedEntry := bytes.TrimSpace(entry)
		if len(trimmedEntry) == 0 || trimmedEntry[0] != '{' || string(trimmedEntry) == "null" {
			return nil, ErrKeyResolutionFailed
		}

		var keyMap map[string]json.RawMessage
		if err := json.Unmarshal(entry, &keyMap); err != nil {
			return nil, ErrKeyResolutionFailed
		}

		// Reject private key member "d"
		if _, hasD := keyMap["d"]; hasD {
			return nil, ErrKeyResolutionFailed
		}

		// Exact kty = EC
		rawKty, hasKty := keyMap["kty"]
		if !hasKty {
			return nil, ErrKeyResolutionFailed
		}
		var kty string
		if err := json.Unmarshal(rawKty, &kty); err != nil || kty != "EC" {
			return nil, ErrKeyResolutionFailed
		}

		// Exact crv = P-256
		rawCrv, hasCrv := keyMap["crv"]
		if !hasCrv {
			return nil, ErrKeyResolutionFailed
		}
		var crv string
		if err := json.Unmarshal(rawCrv, &crv); err != nil || crv != "P-256" {
			return nil, ErrKeyResolutionFailed
		}

		// Exact kid = nonblank string (not trimmed)
		rawKid, hasKid := keyMap["kid"]
		if !hasKid {
			return nil, ErrKeyResolutionFailed
		}
		var kid string
		if err := json.Unmarshal(rawKid, &kid); err != nil || len(kid) == 0 || strings.TrimSpace(kid) == "" {
			return nil, ErrKeyResolutionFailed
		}
		if _, exists := seenKIDs[kid]; exists {
			return nil, ErrKeyResolutionFailed
		}
		seenKIDs[kid] = struct{}{}

		// Exact use = sig
		rawUse, hasUse := keyMap["use"]
		if !hasUse {
			return nil, ErrKeyResolutionFailed
		}
		var use string
		if err := json.Unmarshal(rawUse, &use); err != nil || use != "sig" {
			return nil, ErrKeyResolutionFailed
		}

		// Exact alg = ES256
		rawAlg, hasAlg := keyMap["alg"]
		if !hasAlg {
			return nil, ErrKeyResolutionFailed
		}
		var alg string
		if err := json.Unmarshal(rawAlg, &alg); err != nil || alg != "ES256" {
			return nil, ErrKeyResolutionFailed
		}

		// x and y present and non-empty
		rawX, hasX := keyMap["x"]
		rawY, hasY := keyMap["y"]
		if !hasX || !hasY {
			return nil, ErrKeyResolutionFailed
		}
		var xStr, yStr string
		if err := json.Unmarshal(rawX, &xStr); err != nil || len(xStr) == 0 {
			return nil, ErrKeyResolutionFailed
		}
		if err := json.Unmarshal(rawY, &yStr); err != nil || len(yStr) == 0 {
			return nil, ErrKeyResolutionFailed
		}

		// Convert using jwx jwk.ParseKey
		parsedKey, err := jwk.ParseKey(entry)
		if err != nil {
			return nil, ErrKeyResolutionFailed
		}

		isPriv, _ := jwk.IsPrivateKey(parsedKey)
		if isPriv {
			return nil, ErrKeyResolutionFailed
		}

		ecKey, ok := parsedKey.(jwk.ECDSAPublicKey)
		if !ok {
			return nil, ErrKeyResolutionFailed
		}

		rawXBytes, okX := ecKey.X()
		rawYBytes, okY := ecKey.Y()
		if !okX || !okY || len(rawXBytes) != 32 || len(rawYBytes) != 32 {
			return nil, ErrKeyResolutionFailed
		}

		pubKey := &ecdsa.PublicKey{
			Curve: elliptic.P256(),
			X:     new(big.Int).SetBytes(rawXBytes),
			Y:     new(big.Int).SetBytes(rawYBytes),
		}

		if pubKey.Curve != elliptic.P256() {
			return nil, ErrKeyResolutionFailed
		}
		if pubKey.X == nil || pubKey.Y == nil || !pubKey.Curve.IsOnCurve(pubKey.X, pubKey.Y) {
			return nil, ErrKeyResolutionFailed
		}

		tempKeys[kid] = copyECDSAPublicKey(pubKey)
	}

	return tempKeys, nil
}

func parseCacheControlTTL(header http.Header, policy JWKSResolverPolicy) time.Duration {
	ccValues := header.Values("Cache-Control")
	if len(ccValues) == 0 {
		return policy.DefaultTTL
	}

	var (
		hasValidMaxAge   bool
		minValidTTL      time.Duration
		hasInvalidMaxAge bool
	)

	const maxSec = math.MaxInt64 / int64(time.Second)

	for _, cc := range ccValues {
		directives := strings.Split(cc, ",")
		for _, dir := range directives {
			dir = strings.TrimSpace(dir)
			if idx := strings.Index(dir, "="); idx != -1 {
				name := strings.ToLower(strings.TrimSpace(dir[:idx]))
				if name == "max-age" {
					rawVal := strings.TrimSpace(dir[idx+1:])
					rawVal = strings.Trim(rawVal, "\"")
					sec, err := strconv.ParseInt(rawVal, 10, 64)
					if err != nil || sec < 0 || sec > maxSec {
						hasInvalidMaxAge = true
						continue
					}
					parsed := time.Duration(sec) * time.Second
					if parsed > policy.MaxTTL {
						parsed = policy.MaxTTL
					}
					if !hasValidMaxAge || parsed < minValidTTL {
						minValidTTL = parsed
					}
					hasValidMaxAge = true
				}
			} else {
				if strings.EqualFold(dir, "max-age") {
					hasInvalidMaxAge = true
				}
			}
		}
	}

	var effectiveTTL time.Duration
	if hasValidMaxAge {
		if hasInvalidMaxAge && minValidTTL > policy.DefaultTTL {
			effectiveTTL = policy.DefaultTTL
		} else {
			effectiveTTL = minValidTTL
		}
	} else {
		effectiveTTL = policy.DefaultTTL
	}

	if effectiveTTL < 0 {
		effectiveTTL = 0
	}
	if effectiveTTL > policy.MaxTTL {
		effectiveTTL = policy.MaxTTL
	}

	return effectiveTTL
}

func copyECDSAPublicKey(k *ecdsa.PublicKey) *ecdsa.PublicKey {
	if k == nil {
		return nil
	}
	cp := &ecdsa.PublicKey{
		Curve: k.Curve,
	}
	if k.X != nil {
		cp.X = new(big.Int).Set(k.X)
	}
	if k.Y != nil {
		cp.Y = new(big.Int).Set(k.Y)
	}
	return cp
}
