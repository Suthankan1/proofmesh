package canonicalize

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"

	"github.com/deszhou/jcs"
)

var (
	// ErrBlankPayload indicates the input payload is empty or whitespace-only.
	ErrBlankPayload = errors.New("payload is blank")

	// ErrRootNotObject indicates the JSON root value is not an object.
	ErrRootNotObject = errors.New("payload root must be a JSON object")

	// ErrCanonicalizationFailed indicates canonicalization failed due to malformed JSON,
	// duplicate keys, or syntax errors.
	ErrCanonicalizationFailed = errors.New("payload canonicalization failed")
)

func isJSONWhitespace(b byte) bool {
	return b == ' ' || b == '\t' || b == '\n' || b == '\r'
}

func trimJSONWhitespace(b []byte) []byte {
	start := 0
	for start < len(b) && isJSONWhitespace(b[start]) {
		start++
	}
	end := len(b)
	for end > start && isJSONWhitespace(b[end-1]) {
		end--
	}
	return b[start:end]
}

// CanonicalizeAndHash canonicalizes a JSON object payload per RFC 8785 (JCS)
// and computes its SHA-256 hash as a 64-character lowercase hexadecimal string.
//
// ProofMesh governance payloads must be JSON objects at the root.
// Inputs with root arrays, scalars, null, blank content, malformed JSON,
// or duplicate object keys are rejected.
//
// Returned errors do not echo caller payload contents or retain raw parser causes.
func CanonicalizeAndHash(payload []byte) ([]byte, string, error) {
	trimmed := trimJSONWhitespace(payload)
	if len(trimmed) == 0 {
		return nil, "", ErrBlankPayload
	}

	if trimmed[0] != '{' || trimmed[len(trimmed)-1] != '}' {
		return nil, "", ErrRootNotObject
	}

	canonical, err := jcs.Transform(payload)
	if err != nil {
		return nil, "", ErrCanonicalizationFailed
	}

	if len(canonical) < 2 || canonical[0] != '{' || canonical[len(canonical)-1] != '}' {
		return nil, "", ErrRootNotObject
	}

	sum := sha256.Sum256(canonical)
	hashHex := hex.EncodeToString(sum[:])

	return canonical, hashHex, nil
}
