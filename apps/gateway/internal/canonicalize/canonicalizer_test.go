package canonicalize

import (
	"bytes"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"testing"
)

var sha256HexPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// -----------------------------------------------------------------------------
// Java Control-Plane Parity Vectors
// -----------------------------------------------------------------------------

func TestCanonicalizeAndHash_JavaReferenceVector(t *testing.T) {
	// Vector from Java: Rfc8785RequestPayloadCanonicalizerTest.canonicalizesObjectAndCalculatesSha256
	input := []byte(`{
  "paymentId": "pay_123",
  "amount": 5000
}`)
	expectedCanonical := `{"amount":5000,"paymentId":"pay_123"}`
	expectedHash := "fe033f2b85fbcc6f563d9ecdcb7d04e40e3a8ef239f900db3b0b34bbe3e21ff7"

	canonical, hashHex, err := CanonicalizeAndHash(input)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if string(canonical) != expectedCanonical {
		t.Errorf("canonical mismatch:\ngot:  %s\nwant: %s", string(canonical), expectedCanonical)
	}

	if hashHex != expectedHash {
		t.Errorf("hash mismatch:\ngot:  %s\nwant: %s", hashHex, expectedHash)
	}

	if len(hashHex) != 64 {
		t.Errorf("expected 64 hex characters, got %d", len(hashHex))
	}
	if !sha256HexPattern.MatchString(hashHex) {
		t.Errorf("hash is not 64 lowercase hex characters: %s", hashHex)
	}
	if bytes.HasSuffix(canonical, []byte("\n")) {
		t.Error("canonical output must not contain a trailing newline")
	}
}

func TestCanonicalizeAndHash_JavaPropertyOrderInvariance(t *testing.T) {
	// Vector from Java: Rfc8785RequestPayloadCanonicalizerTest.producesSameIdentityRegardlessOfObjectPropertyOrder
	first := []byte(`{
  "paymentId": "pay_123",
  "amount": 5000
}`)
	second := []byte(`{
  "amount": 5000,
  "paymentId": "pay_123"
}`)

	firstCanonical, firstHash, err1 := CanonicalizeAndHash(first)
	if err1 != nil {
		t.Fatalf("unexpected error for first: %v", err1)
	}

	secondCanonical, secondHash, err2 := CanonicalizeAndHash(second)
	if err2 != nil {
		t.Fatalf("unexpected error for second: %v", err2)
	}

	if !bytes.Equal(firstCanonical, secondCanonical) {
		t.Errorf("canonical mismatch between permutations:\nfirst:  %s\nsecond: %s", string(firstCanonical), string(secondCanonical))
	}

	if firstHash != secondHash {
		t.Errorf("hash mismatch between permutations:\nfirst:  %s\nsecond: %s", firstHash, secondHash)
	}
}

func TestCanonicalizeAndHash_JavaSemanticChangeProducesDifferentHash(t *testing.T) {
	// Vector from Java: Rfc8785RequestPayloadCanonicalizerTest.changesIdentityWhenPayloadChanges
	first := []byte(`{
  "amount": 5000
}`)
	second := []byte(`{
  "amount": 5001
}`)

	_, firstHash, err1 := CanonicalizeAndHash(first)
	if err1 != nil {
		t.Fatalf("unexpected error for first: %v", err1)
	}

	_, secondHash, err2 := CanonicalizeAndHash(second)
	if err2 != nil {
		t.Fatalf("unexpected error for second: %v", err2)
	}

	if firstHash == secondHash {
		t.Errorf("expected different hashes for different payloads, got both %s", firstHash)
	}
}

func TestCanonicalizeAndHash_JavaPolicyVectors(t *testing.T) {
	// Vectors from Java: Rfc8785PolicyDefinitionCanonicalizerTest
	tests := []struct {
		name              string
		input             []byte
		expectedCanonical string
		expectedHash      string
	}{
		{
			name:              "empty rules array in policy object",
			input:             []byte(`{"rules":[]}`),
			expectedCanonical: `{"rules":[]}`,
			expectedHash:      "da506c8a9c8a9f31aa00eaeef23d49764b9ace97158a1a0a7aa628e6d446b0fb",
		},
		{
			name: "policy definition with rules and targets",
			input: []byte(`{
  "rules": [
    {
      "effect": "REQUIRE_APPROVAL",
      "id": "51000000-0000-0000-0000-000000000001",
      "priority": 100,
      "reasonCode": "HIGH_RISK_REFUND",
      "risk": { "minimum": 80 },
      "target": { "operation": "refund_payment", "tool": "stripe" }
    },
    {
      "effect": "ALLOW",
      "id": "51000000-0000-0000-0000-000000000002",
      "priority": 200,
      "reasonCode": "STANDARD_REFUND",
      "risk": { "minimum": 0 },
      "target": { "operation": "refund_payment", "tool": "stripe" }
    }
  ]
}`),
			expectedCanonical: `{"rules":[{"effect":"REQUIRE_APPROVAL","id":"51000000-0000-0000-0000-000000000001","priority":100,"reasonCode":"HIGH_RISK_REFUND","risk":{"minimum":80},"target":{"operation":"refund_payment","tool":"stripe"}},{"effect":"ALLOW","id":"51000000-0000-0000-0000-000000000002","priority":200,"reasonCode":"STANDARD_REFUND","risk":{"minimum":0},"target":{"operation":"refund_payment","tool":"stripe"}}]}`,
			expectedHash:      "46f7bc30cea7ca4977c46b4cfb5399a3ecd53bbe73b19f499f17f3b6ae578bd4",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			canonical, hashHex, err := CanonicalizeAndHash(tt.input)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if string(canonical) != tt.expectedCanonical {
				t.Errorf("canonical mismatch:\ngot:  %s\nwant: %s", string(canonical), tt.expectedCanonical)
			}
			if hashHex != tt.expectedHash {
				t.Errorf("hash mismatch:\ngot:  %s\nwant: %s", hashHex, tt.expectedHash)
			}
		})
	}
}

// -----------------------------------------------------------------------------
// Additional Compatibility Tests (Whitespace, Nesting, Arrays, Unicode, Numbers)
// -----------------------------------------------------------------------------

func TestCanonicalizeAndHash_WhitespaceInvariance(t *testing.T) {
	compact := []byte(`{"a":1,"b":2}`)
	spaced := []byte(`   {  "a" :  1 ,  "b" : 2  }   `)
	newlines := []byte("{\n\t\"a\": 1,\r\n\t\"b\": 2\n}\n")

	c1, h1, err1 := CanonicalizeAndHash(compact)
	if err1 != nil {
		t.Fatalf("unexpected error: %v", err1)
	}
	c2, h2, err2 := CanonicalizeAndHash(spaced)
	if err2 != nil {
		t.Fatalf("unexpected error: %v", err2)
	}
	c3, h3, err3 := CanonicalizeAndHash(newlines)
	if err3 != nil {
		t.Fatalf("unexpected error: %v", err3)
	}

	if !bytes.Equal(c1, c2) || !bytes.Equal(c1, c3) {
		t.Errorf("whitespace affected canonical output: c1=%s, c2=%s, c3=%s", c1, c2, c3)
	}
	if h1 != h2 || h1 != h3 {
		t.Errorf("whitespace affected hash: h1=%s, h2=%s, h3=%s", h1, h2, h3)
	}
}

func TestCanonicalizeAndHash_NestedObjects(t *testing.T) {
	input := []byte(`{
  "z": { "b": 2, "a": 1 },
  "a": { "d": 4, "c": 3 }
}`)
	expected := `{"a":{"c":3,"d":4},"z":{"a":1,"b":2}}`

	canonical, _, err := CanonicalizeAndHash(input)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if string(canonical) != expected {
		t.Errorf("nested object canonical mismatch:\ngot:  %s\nwant: %s", string(canonical), expected)
	}
}

func TestCanonicalizeAndHash_ArrayElementOrderPreserved(t *testing.T) {
	// Arrays inside root object must preserve element order, but objects inside arrays are sorted.
	input := []byte(`{
  "list": [3, 1, 2],
  "complex": [
    { "z": 9, "a": 8 },
    { "y": 7, "b": 6 }
  ]
}`)
	expected := `{"complex":[{"a":8,"z":9},{"b":6,"y":7}],"list":[3,1,2]}`

	canonical, _, err := CanonicalizeAndHash(input)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if string(canonical) != expected {
		t.Errorf("array ordering mismatch:\ngot:  %s\nwant: %s", string(canonical), expected)
	}
}

func TestCanonicalizeAndHash_RFC8785StructuresVector(t *testing.T) {
	// Test vector from RFC 8785 reference suite (structures.json)
	input := []byte(`{
  "1": {"f": {"f": "hi","F": 5} ,"\n": 56.0},
  "10": { },
  "": "empty",
  "a": { },
  "111": [ {"e": "yes","E": "no" } ],
  "A": { }
}`)
	expected := `{"":"empty","1":{"\n":56,"f":{"F":5,"f":"hi"}},"10":{},"111":[{"E":"no","e":"yes"}],"A":{},"a":{}}`

	canonical, hashHex, err := CanonicalizeAndHash(input)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if string(canonical) != expected {
		t.Errorf("structures vector mismatch:\ngot:  %s\nwant: %s", string(canonical), expected)
	}
	if !sha256HexPattern.MatchString(hashHex) {
		t.Errorf("invalid hash hex: %s", hashHex)
	}
}

func TestCanonicalizeAndHash_RFC8785ValuesVector(t *testing.T) {
	// Test vector from RFC 8785 reference suite (values.json)
	input := []byte(`{
  "numbers": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],
  "string": "\u20ac$\u000F\u000aA'\u0042\u0022\u005c\\\"\/",
  "literals": [null, true, false]
}`)
	expected := `{"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],"string":"€$\u000f\nA'B\"\\\\\"/"}`

	canonical, _, err := CanonicalizeAndHash(input)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if string(canonical) != expected {
		t.Errorf("values vector mismatch:\ngot:  %s\nwant: %s", string(canonical), expected)
	}
}

func TestCanonicalizeAndHash_RFC8785UnicodeVector(t *testing.T) {
	// Test vector from RFC 8785 reference suite (unicode.json)
	input := []byte(`{
  "Unnormalized Unicode":"A\u030a"
}`)
	expected := `{"Unnormalized Unicode":"Å"}`

	canonical, _, err := CanonicalizeAndHash(input)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if string(canonical) != expected {
		t.Errorf("unicode vector mismatch:\ngot:  %s\nwant: %s", string(canonical), expected)
	}
}

func TestCanonicalizeAndHash_EmptyObject(t *testing.T) {
	input := []byte(`{}`)
	expectedCanonical := `{}`
	// SHA-256 of "{}" is 44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a
	expectedHash := "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"

	canonical, hashHex, err := CanonicalizeAndHash(input)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if string(canonical) != expectedCanonical {
		t.Errorf("canonical mismatch:\ngot:  %s\nwant: %s", string(canonical), expectedCanonical)
	}
	if hashHex != expectedHash {
		t.Errorf("hash mismatch:\ngot:  %s\nwant: %s", hashHex, expectedHash)
	}
}

// -----------------------------------------------------------------------------
// Rejection Tests (Blank, Root Non-Object, Duplicate Keys, Malformed, Trailing)
// -----------------------------------------------------------------------------

func TestCanonicalizeAndHash_RejectsBlank(t *testing.T) {
	blankInputs := [][]byte{
		nil,
		{},
		[]byte(""),
		[]byte(" "),
		[]byte("\t"),
		[]byte("\n"),
		[]byte("\r\n"),
		[]byte("   \t\r\n   "),
	}

	for _, input := range blankInputs {
		canonical, hashHex, err := CanonicalizeAndHash(input)
		if err == nil {
			t.Errorf("expected error for blank input %q, got nil", string(input))
		}
		if !errors.Is(err, ErrBlankPayload) {
			t.Errorf("expected ErrBlankPayload for %q, got %v", string(input), err)
		}
		if canonical != nil {
			t.Errorf("expected nil canonical for %q, got %v", string(input), canonical)
		}
		if hashHex != "" {
			t.Errorf("expected empty hash for %q, got %s", string(input), hashHex)
		}
	}
}

func TestCanonicalizeAndHash_RejectsNonObjectRoots(t *testing.T) {
	tests := []struct {
		name  string
		input []byte
	}{
		{name: "root array", input: []byte(`[{"amount": 5000}]`)},
		{name: "empty array", input: []byte(`[]`)},
		{name: "root string", input: []byte(`"hello world"`)},
		{name: "root number integer", input: []byte(`12345`)},
		{name: "root number float", input: []byte(`123.45`)},
		{name: "root null", input: []byte(`null`)},
		{name: "root true", input: []byte(`true`)},
		{name: "root false", input: []byte(`false`)},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			canonical, hashHex, err := CanonicalizeAndHash(tt.input)
			if err == nil {
				t.Fatalf("expected error for %s, got nil", tt.name)
			}
			if !errors.Is(err, ErrRootNotObject) {
				t.Errorf("expected ErrRootNotObject for %s, got %v", tt.name, err)
			}
			if canonical != nil {
				t.Errorf("expected nil canonical, got %s", string(canonical))
			}
			if hashHex != "" {
				t.Errorf("expected empty hash, got %s", hashHex)
			}
		})
	}
}

func TestCanonicalizeAndHash_RejectsDuplicateKeys(t *testing.T) {
	tests := []struct {
		name  string
		input []byte
	}{
		{
			name:  "root duplicate keys",
			input: []byte(`{"amount": 5000, "amount": 9000}`),
		},
		{
			name:  "nested duplicate keys",
			input: []byte(`{"nested": {"foo": "bar", "foo": "baz"}}`),
		},
		{
			name:  "duplicate keys inside array",
			input: []byte(`{"items": [{"k": 1, "k": 2}]}`),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			canonical, hashHex, err := CanonicalizeAndHash(tt.input)
			if err == nil {
				t.Fatalf("expected error for duplicate key %s, got nil", tt.name)
			}
			if !errors.Is(err, ErrCanonicalizationFailed) {
				t.Errorf("expected ErrCanonicalizationFailed, got %v", err)
			}
			if canonical != nil {
				t.Errorf("expected nil canonical, got %s", string(canonical))
			}
			if hashHex != "" {
				t.Errorf("expected empty hash, got %s", hashHex)
			}
		})
	}
}

func TestCanonicalizeAndHash_RejectsMalformedJson(t *testing.T) {
	tests := []struct {
		name  string
		input []byte
	}{
		{name: "unterminated object", input: []byte(`{"amount": 5000`)},
		{name: "missing value", input: []byte(`{"amount": }`)},
		{name: "missing colon", input: []byte(`{"amount" 5000}`)},
		{name: "unquoted key", input: []byte(`{amount: 5000}`)},
		{name: "trailing comma", input: []byte(`{"amount": 5000,}`)},
		{name: "invalid number", input: []byte(`{"amount": 1.2.3}`)},
		{name: "unterminated string", input: []byte(`{"key": "value}`)},
		{name: "lone surrogate", input: []byte(`{"key": "\uD800"}`)},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			canonical, hashHex, err := CanonicalizeAndHash(tt.input)
			if err == nil {
				t.Fatalf("expected error for malformed json %s, got nil", tt.name)
			}
			if canonical != nil {
				t.Errorf("expected nil canonical, got %s", string(canonical))
			}
			if hashHex != "" {
				t.Errorf("expected empty hash, got %s", hashHex)
			}
		})
	}
}

func TestCanonicalizeAndHash_RejectsTrailingContent(t *testing.T) {
	tests := []struct {
		name  string
		input []byte
	}{
		{
			name:  "trailing string after object",
			input: []byte(`{"paymentId": "pay_123"} trailing`),
		},
		{
			name:  "second object trailing",
			input: []byte(`{"paymentId": "pay_123"} {"second": 1}`),
		},
		{
			name:  "trailing semicolon",
			input: []byte(`{"paymentId": "pay_123"};`),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			canonical, hashHex, err := CanonicalizeAndHash(tt.input)
			if err == nil {
				t.Fatalf("expected error for trailing content in %s, got nil", tt.name)
			}
			if canonical != nil {
				t.Errorf("expected nil canonical, got %s", string(canonical))
			}
			if hashHex != "" {
				t.Errorf("expected empty hash, got %s", hashHex)
			}
		})
	}
}

// -----------------------------------------------------------------------------
// Error Redaction Tests (Secret Marker Non-Leak)
// -----------------------------------------------------------------------------

func TestCanonicalizeAndHash_ErrorRedaction(t *testing.T) {
	const secretMarker = "SUPER_SECRET_TOKEN_DO_NOT_LEAK_12345"

	tests := []struct {
		name         string
		input        []byte
		expectedKind error
	}{
		{
			name:         "secret in duplicate key",
			input:        []byte(fmt.Sprintf(`{"%s": 1, "%s": 2}`, secretMarker, secretMarker)),
			expectedKind: ErrCanonicalizationFailed,
		},
		{
			name:         "secret in malformed value",
			input:        []byte(fmt.Sprintf(`{"key": %s}`, secretMarker)),
			expectedKind: ErrCanonicalizationFailed,
		},
		{
			name:         "secret in duplicate nested key",
			input:        []byte(fmt.Sprintf(`{"nested": {"%s": 1, "%s": 2}}`, secretMarker, secretMarker)),
			expectedKind: ErrCanonicalizationFailed,
		},
		{
			name:         "secret in unterminated string",
			input:        []byte(fmt.Sprintf(`{"key": "%s}`, secretMarker)),
			expectedKind: ErrCanonicalizationFailed,
		},
		{
			name:         "secret in trailing content",
			input:        []byte(fmt.Sprintf(`{"key": 1} %s`, secretMarker)),
			expectedKind: ErrRootNotObject,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, _, err := CanonicalizeAndHash(tt.input)
			if err == nil {
				t.Fatal("expected error, got nil")
			}

			if !errors.Is(err, tt.expectedKind) {
				t.Errorf("expected error matching %v, got %v", tt.expectedKind, err)
			}

			errString := err.Error()
			if strings.Contains(errString, secretMarker) {
				t.Fatalf("SECURITY VIOLATION: error message leaks secret marker: %s", errString)
			}

			formatted := fmt.Sprintf("%v", err)
			if strings.Contains(formatted, secretMarker) {
				t.Fatalf("SECURITY VIOLATION: formatted error leaks secret marker: %s", formatted)
			}

			plusFormatted := fmt.Sprintf("%+v", err)
			if strings.Contains(plusFormatted, secretMarker) {
				t.Fatalf("SECURITY VIOLATION: formatted error (%%+v) leaks secret marker: %s", plusFormatted)
			}

			if unwrapped := errors.Unwrap(err); unwrapped != nil {
				t.Fatalf("SECURITY VIOLATION: raw error cause exposed via errors.Unwrap: %v", unwrapped)
			}
		})
	}
}

func TestCanonicalizeAndHash_NoRawParserErrorExposure(t *testing.T) {
	// Verify that malformed/duplicate-key errors do not expose raw parser diagnostics
	// through Unwrap() or any Cause() accessor.
	_, _, err := CanonicalizeAndHash([]byte(`{"duplicate": 1, "duplicate": 2}`))
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	if !errors.Is(err, ErrCanonicalizationFailed) {
		t.Fatalf("expected ErrCanonicalizationFailed, got %v", err)
	}

	if unwrapped := errors.Unwrap(err); unwrapped != nil {
		t.Fatalf("expected no unwrappable cause, got: %v", unwrapped)
	}

	type causer interface {
		Cause() error
	}
	if c, ok := err.(causer); ok {
		t.Fatalf("error must not implement Cause() error, but does: %v", c.Cause())
	}
}
