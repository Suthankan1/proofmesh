package executiongrant

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestVerifiedExecutionGrant_StringRedaction(t *testing.T) {
	grantID := uuid.New()
	orgID := uuid.New()
	agentID := uuid.New()
	actionID := uuid.New()
	decisionID := uuid.New()

	grant := VerifiedExecutionGrant{
		GrantID:              grantID,
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decisionID,
		ToolName:             "payments",
		OperationName:        "refund",
		PayloadHash:          "fe033f2b85fbcc6f563d9ecdcb7d04e40e3a8ef239f900db3b0b34bbe3e21ff7",
		Issuer:               "proofmesh-control-plane",
		Audience:             "proofmesh-gateway",
		IssuedAt:             time.Unix(1725530400, 0).UTC(),
		ExpiresAt:            time.Unix(1725530430, 0).UTC(),
		KeyID:                "test-key-1",
	}

	str := grant.String()
	if !strings.Contains(str, grantID.String()) {
		t.Fatalf("expected String() to contain grant ID, got: %s", str)
	}
	if !strings.Contains(str, "payments") {
		t.Fatalf("expected String() to contain tool name, got: %s", str)
	}
}

func TestValidatePayloadHash(t *testing.T) {
	valid := "fe033f2b85fbcc6f563d9ecdcb7d04e40e3a8ef239f900db3b0b34bbe3e21ff7"
	if err := validatePayloadHash(valid); err != nil {
		t.Fatalf("expected valid payload hash to pass, got: %v", err)
	}

	invalidCases := []struct {
		name string
		hash string
	}{
		{"uppercase", strings.ToUpper(valid)},
		{"mixed_case", "Fe033f2b85fbcc6f563d9ecdcb7d04e40e3a8ef239f900db3b0b34bbe3e21ff7"},
		{"too_short_63", valid[:63]},
		{"too_long_65", valid + "a"},
		{"leading_0x", "0x" + valid[2:]},
		{"with_whitespace", " " + valid},
		{"trailing_whitespace", valid + " "},
		{"non_hex", strings.Replace(valid, "a", "z", 1)},
		{"empty", ""},
	}

	for _, tc := range invalidCases {
		t.Run(tc.name, func(t *testing.T) {
			if err := validatePayloadHash(tc.hash); err == nil {
				t.Fatalf("expected payload hash %q to fail validation", tc.hash)
			}
		})
	}
}

func TestParseNonNilUUID(t *testing.T) {
	valid := uuid.New().String()
	id, err := parseNonNilUUID(valid)
	if err != nil {
		t.Fatalf("expected valid UUID to parse, got err: %v", err)
	}
	if id.String() != valid {
		t.Fatalf("expected parsed UUID to match input")
	}

	if _, err := parseNonNilUUID(uuid.Nil.String()); err == nil {
		t.Fatalf("expected nil UUID to fail validation")
	}
	if _, err := parseNonNilUUID("not-a-uuid"); err == nil {
		t.Fatalf("expected malformed UUID string to fail validation")
	}
	if _, err := parseNonNilUUID(""); err == nil {
		t.Fatalf("expected empty string to fail validation")
	}
}

func TestValidateNonBlankString(t *testing.T) {
	if err := validateNonBlankString("valid"); err != nil {
		t.Fatalf("expected valid string to pass, got: %v", err)
	}
	if err := validateNonBlankString(""); err == nil {
		t.Fatalf("expected empty string to fail")
	}
	if err := validateNonBlankString("   "); err == nil {
		t.Fatalf("expected whitespace-only string to fail")
	}
}
