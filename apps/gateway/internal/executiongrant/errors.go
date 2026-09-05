package executiongrant

import "errors"

var (
	// ErrMissingToken indicates the provided token string is empty or whitespace-only.
	ErrMissingToken = errors.New("execution grant token is missing or empty")

	// ErrMalformedToken indicates the token cannot be parsed into a valid compact JWS structure.
	ErrMalformedToken = errors.New("execution grant token is malformed")

	// ErrUnsupportedAlgorithm indicates the token alg is not the strictly pinned ES256 algorithm.
	ErrUnsupportedAlgorithm = errors.New("execution grant uses unsupported signature algorithm")

	// ErrInvalidType indicates the token typ header is missing, blank, or does not exactly match proofmesh-execution-grant+jwt.
	ErrInvalidType = errors.New("execution grant header typ is invalid")

	// ErrMissingKeyID indicates the token kid header is missing, non-string, or blank.
	ErrMissingKeyID = errors.New("execution grant header kid is missing or blank")

	// ErrUnknownKey indicates the key ID is not recognized by the trusted key resolver.
	ErrUnknownKey = errors.New("execution grant signing key is unknown")

	// ErrKeyResolutionFailed indicates the key resolver encountered an unexpected failure.
	ErrKeyResolutionFailed = errors.New("execution grant key resolution failed")

	// ErrInvalidVerificationKey indicates the resolved key is nil, not ECDSA P-256, or contains an invalid curve point.
	ErrInvalidVerificationKey = errors.New("execution grant verification key is invalid")

	// ErrInvalidSignature indicates the signature verification failed.
	ErrInvalidSignature = errors.New("execution grant signature is invalid")

	// ErrInvalidIssuer indicates the iss claim does not match the configured expected issuer.
	ErrInvalidIssuer = errors.New("execution grant issuer is invalid")

	// ErrInvalidAudience indicates the aud claim does not match the configured expected audience.
	ErrInvalidAudience = errors.New("execution grant audience is invalid")

	// ErrMissingClaim indicates one of the required standard or custom claims is absent.
	ErrMissingClaim = errors.New("execution grant is missing required claim")

	// ErrInvalidClaim indicates a claim value has an invalid format, wrong type, or violates domain rules.
	ErrInvalidClaim = errors.New("execution grant claim is invalid")

	// ErrTokenExpired indicates the current gateway time is at or past the token exp claim.
	ErrTokenExpired = errors.New("execution grant is expired")

	// ErrInvalidValidityWindow indicates the token validity window is collapsed or invalid (e.g. exp <= iat).
	ErrInvalidValidityWindow = errors.New("execution grant validity window is invalid")
)
