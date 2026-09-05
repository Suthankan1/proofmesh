package executionattempt_test

import (
	"bytes"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executionattempt"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
)

const (
	knownRawPayload   = `{"paymentId":"pay_123","amount":5000}`
	knownCanonPayload = `{"amount":5000,"paymentId":"pay_123"}`
	knownPayloadHash  = "fe033f2b85fbcc6f563d9ecdcb7d04e40e3a8ef239f900db3b0b34bbe3e21ff7"
)

func validTestGrant(now time.Time) executiongrant.VerifiedExecutionGrant {
	return executiongrant.VerifiedExecutionGrant{
		GrantID:              uuid.MustParse("11111111-1111-1111-1111-111111111111"),
		OrganizationID:       uuid.MustParse("22222222-2222-2222-2222-222222222222"),
		AgentID:              uuid.MustParse("33333333-3333-3333-3333-333333333333"),
		GovernedActionID:     uuid.MustParse("44444444-4444-4444-4444-444444444444"),
		GovernanceDecisionID: uuid.MustParse("55555555-5555-5555-5555-555555555555"),
		ToolName:             "payments",
		OperationName:        "execute",
		PayloadHash:          knownPayloadHash,
		Issuer:               "https://control-plane.proofmesh.local",
		Audience:             "proofmesh-gateway",
		IssuedAt:             now.Add(-10 * time.Second),
		ExpiresAt:            now.Add(20 * time.Second),
		KeyID:                "test-kid-1",
	}
}

func validTestAttempt(grant executiongrant.VerifiedExecutionGrant) executionattempt.ExecutionAttempt {
	return executionattempt.ExecutionAttempt{
		OrganizationID:       grant.OrganizationID,
		AgentID:              grant.AgentID,
		GovernedActionID:     grant.GovernedActionID,
		GovernanceDecisionID: grant.GovernanceDecisionID,
		ToolName:             grant.ToolName,
		OperationName:        grant.OperationName,
		Payload:              []byte(knownRawPayload),
	}
}

func TestBind_Positive(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)
	attempt := validTestAttempt(grant)

	bound, err := executionattempt.Bind(grant, attempt)
	if err != nil {
		t.Fatalf("expected successful bind, got error: %v", err)
	}

	if bound.GrantID() != grant.GrantID {
		t.Errorf("GrantID mismatch: got %s, want %s", bound.GrantID(), grant.GrantID)
	}
	if bound.OrganizationID() != grant.OrganizationID {
		t.Errorf("OrganizationID mismatch: got %s, want %s", bound.OrganizationID(), grant.OrganizationID)
	}
	if bound.AgentID() != grant.AgentID {
		t.Errorf("AgentID mismatch: got %s, want %s", bound.AgentID(), grant.AgentID)
	}
	if bound.GovernedActionID() != grant.GovernedActionID {
		t.Errorf("GovernedActionID mismatch: got %s, want %s", bound.GovernedActionID(), grant.GovernedActionID)
	}
	if bound.GovernanceDecisionID() != grant.GovernanceDecisionID {
		t.Errorf("GovernanceDecisionID mismatch: got %s, want %s", bound.GovernanceDecisionID(), grant.GovernanceDecisionID)
	}
	if bound.ToolName() != grant.ToolName {
		t.Errorf("ToolName mismatch: got %s, want %s", bound.ToolName(), grant.ToolName)
	}
	if bound.OperationName() != grant.OperationName {
		t.Errorf("OperationName mismatch: got %s, want %s", bound.OperationName(), grant.OperationName)
	}
	if bound.PayloadHash() != knownPayloadHash {
		t.Errorf("PayloadHash mismatch: got %s, want %s", bound.PayloadHash(), knownPayloadHash)
	}
	if !bound.ExpiresAt().Equal(grant.ExpiresAt) {
		t.Errorf("ExpiresAt mismatch: got %v, want %v", bound.ExpiresAt(), grant.ExpiresAt)
	}
	if !bytes.Equal(bound.Payload(), []byte(knownCanonPayload)) {
		t.Errorf("Payload mismatch: got %s, want %s", string(bound.Payload()), knownCanonPayload)
	}
}

func TestBind_FormattingInvariance(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)

	variations := []struct {
		name    string
		payload string
	}{
		{
			name:    "reordered keys",
			payload: `{"amount":5000,"paymentId":"pay_123"}`,
		},
		{
			name:    "newlines and indentation",
			payload: "{\n  \"paymentId\": \"pay_123\",\n  \"amount\": 5000\n}",
		},
		{
			name:    "tabs and trailing spaces",
			payload: "{\t\"paymentId\" : \"pay_123\" ,\n \"amount\" : 5000 \t\r\n}",
		},
		{
			name:    "spaces around colon and commas",
			payload: `{   "amount"   :   5000   ,   "paymentId"   :   "pay_123"   }`,
		},
	}

	for _, tc := range variations {
		t.Run(tc.name, func(t *testing.T) {
			attempt := validTestAttempt(grant)
			attempt.Payload = []byte(tc.payload)

			bound, err := executionattempt.Bind(grant, attempt)
			if err != nil {
				t.Fatalf("expected successful bind for %s, got: %v", tc.name, err)
			}
			if bound.PayloadHash() != knownPayloadHash {
				t.Errorf("expected hash %s, got %s", knownPayloadHash, bound.PayloadHash())
			}
			if !bytes.Equal(bound.Payload(), []byte(knownCanonPayload)) {
				t.Errorf("expected canonical payload %s, got %s", knownCanonPayload, string(bound.Payload()))
			}
		})
	}
}

func TestBind_SemanticMismatch(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)

	variations := []struct {
		name    string
		payload string
	}{
		{
			name:    "different amount",
			payload: `{"paymentId":"pay_123","amount":5001}`,
		},
		{
			name:    "different paymentId",
			payload: `{"paymentId":"pay_456","amount":5000}`,
		},
		{
			name:    "extra field",
			payload: `{"paymentId":"pay_123","amount":5000,"currency":"USD"}`,
		},
		{
			name:    "missing field",
			payload: `{"paymentId":"pay_123"}`,
		},
		{
			name:    "array order change",
			payload: `{"items":[1,2]}`,
		},
	}

	for _, tc := range variations {
		t.Run(tc.name, func(t *testing.T) {
			attempt := validTestAttempt(grant)
			attempt.Payload = []byte(tc.payload)

			_, err := executionattempt.Bind(grant, attempt)
			if err == nil {
				t.Fatalf("expected error for semantic mismatch %s, got nil", tc.name)
			}
			if !errors.Is(err, executionattempt.ErrGrantBindingMismatch) {
				t.Errorf("expected ErrGrantBindingMismatch, got: %v", err)
			}
		})
	}
}

func TestBind_InvalidPayload(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)

	testCases := []struct {
		name    string
		payload []byte
	}{
		{name: "nil payload", payload: nil},
		{name: "empty payload", payload: []byte{}},
		{name: "whitespace only spaces", payload: []byte("    ")},
		{name: "whitespace only tabs and newlines", payload: []byte("\t\r\n  \n")},
		{name: "malformed JSON unclosed object", payload: []byte(`{"amount": 5000`)},
		{name: "malformed JSON syntax error", payload: []byte(`{not valid json}`)},
		{name: "root array", payload: []byte(`["paymentId", "pay_123"]`)},
		{name: "root string", payload: []byte(`"just a string"`)},
		{name: "root number", payload: []byte(`12345`)},
		{name: "root boolean true", payload: []byte(`true`)},
		{name: "root boolean false", payload: []byte(`false`)},
		{name: "root null", payload: []byte(`null`)},
		{name: "duplicate root key", payload: []byte(`{"paymentId":"1","paymentId":"2"}`)},
		{name: "duplicate nested key", payload: []byte(`{"nested":{"k":"1","k":"2"}}`)},
		{name: "trailing content after object", payload: []byte(`{"paymentId":"pay_123","amount":5000} trailing`)},
		{name: "leading content before object", payload: []byte(`leading {"paymentId":"pay_123","amount":5000}`)},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			attempt := validTestAttempt(grant)
			attempt.Payload = tc.payload

			_, err := executionattempt.Bind(grant, attempt)
			if err == nil {
				t.Fatalf("expected ErrInvalidAttemptPayload for %s, got nil", tc.name)
			}
			if !errors.Is(err, executionattempt.ErrInvalidAttemptPayload) {
				t.Errorf("expected ErrInvalidAttemptPayload, got: %v", err)
			}
		})
	}
}

func TestBind_ExactMismatchMatrix(t *testing.T) {
	now := time.Now().UTC()
	baseGrant := validTestGrant(now)

	cases := []struct {
		name   string
		mutate func(a *executionattempt.ExecutionAttempt)
	}{
		{
			name: "OrganizationID mismatch",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.OrganizationID = uuid.New()
			},
		},
		{
			name: "AgentID mismatch",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.AgentID = uuid.New()
			},
		},
		{
			name: "GovernedActionID mismatch",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.GovernedActionID = uuid.New()
			},
		},
		{
			name: "GovernanceDecisionID mismatch",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.GovernanceDecisionID = uuid.New()
			},
		},
		{
			name: "ToolName mismatch",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.ToolName = "different_tool"
			},
		},
		{
			name: "OperationName mismatch",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.OperationName = "different_op"
			},
		},
		{
			name: "PayloadHash mismatch",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.Payload = []byte(`{"paymentId":"different","amount":9999}`)
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			attempt := validTestAttempt(baseGrant)
			tc.mutate(&attempt)

			_, err := executionattempt.Bind(baseGrant, attempt)
			if err == nil {
				t.Fatalf("expected mismatch error for %s, got nil", tc.name)
			}
			if !errors.Is(err, executionattempt.ErrGrantBindingMismatch) {
				t.Errorf("expected ErrGrantBindingMismatch for %s, got: %v", tc.name, err)
			}
		})
	}
}

func TestBind_ExactStrings(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)

	stringCases := []struct {
		name     string
		toolName string
		opName   string
	}{
		{name: "ToolName uppercase", toolName: "Payments", opName: "execute"},
		{name: "ToolName trailing space", toolName: "payments ", opName: "execute"},
		{name: "ToolName leading space", toolName: " payments", opName: "execute"},
		{name: "OperationName uppercase", toolName: "payments", opName: "Execute"},
		{name: "OperationName leading space", toolName: "payments", opName: " execute"},
		{name: "OperationName trailing space", toolName: "payments", opName: "execute "},
	}

	for _, tc := range stringCases {
		t.Run(tc.name, func(t *testing.T) {
			attempt := validTestAttempt(grant)
			attempt.ToolName = tc.toolName
			attempt.OperationName = tc.opName

			_, err := executionattempt.Bind(grant, attempt)
			if err == nil {
				t.Fatalf("expected ErrGrantBindingMismatch for %s, got nil", tc.name)
			}
			if !errors.Is(err, executionattempt.ErrGrantBindingMismatch) {
				t.Errorf("expected ErrGrantBindingMismatch, got: %v", err)
			}
		})
	}
}

func TestBind_NilIdentities(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)

	cases := []struct {
		name   string
		mutate func(a *executionattempt.ExecutionAttempt)
	}{
		{
			name: "Nil OrganizationID",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.OrganizationID = uuid.Nil
			},
		},
		{
			name: "Nil AgentID",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.AgentID = uuid.Nil
			},
		},
		{
			name: "Nil GovernedActionID",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.GovernedActionID = uuid.Nil
			},
		},
		{
			name: "Nil GovernanceDecisionID",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.GovernanceDecisionID = uuid.Nil
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			attempt := validTestAttempt(grant)
			tc.mutate(&attempt)

			_, err := executionattempt.Bind(grant, attempt)
			if err == nil {
				t.Fatalf("expected ErrInvalidExecutionAttempt for %s, got nil", tc.name)
			}
			if !errors.Is(err, executionattempt.ErrInvalidExecutionAttempt) {
				t.Errorf("expected ErrInvalidExecutionAttempt, got: %v", err)
			}
		})
	}
}

func TestBind_EmptyOrWhitespaceToolOperation(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)

	cases := []struct {
		name   string
		mutate func(a *executionattempt.ExecutionAttempt)
	}{
		{
			name: "empty ToolName",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.ToolName = ""
			},
		},
		{
			name: "whitespace ToolName spaces",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.ToolName = "   "
			},
		},
		{
			name: "whitespace ToolName tabs and newlines",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.ToolName = "\t\r\n"
			},
		},
		{
			name: "empty OperationName",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.OperationName = ""
			},
		},
		{
			name: "whitespace OperationName spaces",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.OperationName = "   "
			},
		},
		{
			name: "whitespace OperationName tabs and newlines",
			mutate: func(a *executionattempt.ExecutionAttempt) {
				a.OperationName = "\t\r\n"
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			attempt := validTestAttempt(grant)
			tc.mutate(&attempt)

			_, err := executionattempt.Bind(grant, attempt)
			if err == nil {
				t.Fatalf("expected ErrInvalidExecutionAttempt for %s, got nil", tc.name)
			}
			if !errors.Is(err, executionattempt.ErrInvalidExecutionAttempt) {
				t.Errorf("expected ErrInvalidExecutionAttempt, got: %v", err)
			}
		})
	}
}

func TestBind_TOCTOUAndImmutability(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)

	// 1. Mutating original slice after binding must not affect bound payload
	rawBytes := []byte(knownRawPayload)
	attempt := validTestAttempt(grant)
	attempt.Payload = rawBytes

	bound, err := executionattempt.Bind(grant, attempt)
	if err != nil {
		t.Fatalf("expected successful bind, got: %v", err)
	}

	// Corrupt raw slice
	rawBytes[0] = 'X'
	rawBytes[1] = 'Y'
	rawBytes[2] = 'Z'

	if !bytes.Equal(bound.Payload(), []byte(knownCanonPayload)) {
		t.Errorf("bound payload was corrupted by external mutation! got %s, want %s", string(bound.Payload()), knownCanonPayload)
	}

	// 2. Mutating slice returned from Payload() must not affect subsequent calls
	p1 := bound.Payload()
	p1[0] = 'Q'
	p1[1] = 'W'

	p2 := bound.Payload()
	if !bytes.Equal(p2, []byte(knownCanonPayload)) {
		t.Errorf("bound internal payload was corrupted via getter slice! got %s, want %s", string(p2), knownCanonPayload)
	}
}

func TestBind_Redaction(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)

	const (
		canaryToolSecret    = "CANARY_TOOL_NAME_XYZ_SECRET"
		canaryOpSecret      = "CANARY_OP_NAME_XYZ_SECRET"
		canaryPayloadSecret = "CANARY_PAYLOAD_SECRET_DO_NOT_LEAK"
	)

	t.Run("mismatch_error_redaction", func(t *testing.T) {
		attempt := validTestAttempt(grant)
		attempt.ToolName = canaryToolSecret
		attempt.OperationName = canaryOpSecret

		_, err := executionattempt.Bind(grant, attempt)
		if err == nil {
			t.Fatal("expected error, got nil")
		}

		for _, formatted := range []string{
			err.Error(),
			fmt.Sprintf("%v", err),
			fmt.Sprintf("%+v", err),
		} {
			if strings.Contains(formatted, canaryToolSecret) {
				t.Errorf("mismatch error leaked canary tool secret: %s", formatted)
			}
			if strings.Contains(formatted, canaryOpSecret) {
				t.Errorf("mismatch error leaked canary op secret: %s", formatted)
			}
		}
	})

	t.Run("invalid_payload_error_redaction", func(t *testing.T) {
		attempt := validTestAttempt(grant)
		attempt.Payload = []byte(fmt.Sprintf(`{"%s":"1","%s":"2"}`, canaryPayloadSecret, canaryPayloadSecret))

		_, err := executionattempt.Bind(grant, attempt)
		if err == nil {
			t.Fatal("expected error, got nil")
		}

		for _, formatted := range []string{
			err.Error(),
			fmt.Sprintf("%v", err),
			fmt.Sprintf("%+v", err),
		} {
			if strings.Contains(formatted, canaryPayloadSecret) {
				t.Errorf("invalid payload error leaked canary payload secret: %s", formatted)
			}
		}
	})

	t.Run("invalid_attempt_error_redaction", func(t *testing.T) {
		attempt := validTestAttempt(grant)
		attempt.ToolName = canaryToolSecret
		attempt.OperationName = "" // triggers ErrInvalidExecutionAttempt

		_, err := executionattempt.Bind(grant, attempt)
		if err == nil {
			t.Fatal("expected error, got nil")
		}

		for _, formatted := range []string{
			err.Error(),
			fmt.Sprintf("%v", err),
			fmt.Sprintf("%+v", err),
		} {
			if strings.Contains(formatted, canaryToolSecret) {
				t.Errorf("invalid attempt error leaked canary tool secret: %s", formatted)
			}
		}
	})
}

func TestBind_SafeStringRepresentation(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)
	attempt := validTestAttempt(grant)

	bound, err := executionattempt.Bind(grant, attempt)
	if err != nil {
		t.Fatalf("expected successful bind, got: %v", err)
	}

	str := bound.String()
	if strings.Contains(str, "pay_123") || strings.Contains(str, "5000") {
		t.Errorf("String() representation leaked payload contents: %s", str)
	}
	if !strings.Contains(str, grant.GrantID.String()) {
		t.Errorf("String() missing GrantID: %s", str)
	}
}

func TestBind_BinderStructAndAlias(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)
	attempt := validTestAttempt(grant)

	// Test NewBinder().Bind(...)
	binder := executionattempt.NewBinder()
	bound1, err1 := binder.Bind(grant, attempt)
	if err1 != nil {
		t.Fatalf("NewBinder().Bind failed: %v", err1)
	}

	// Test BindExecutionAttempt(...)
	bound2, err2 := executionattempt.BindExecutionAttempt(grant, attempt)
	if err2 != nil {
		t.Fatalf("BindExecutionAttempt failed: %v", err2)
	}

	if bound1.GrantID() != bound2.GrantID() ||
		bound1.PayloadHash() != bound2.PayloadHash() ||
		!bytes.Equal(bound1.Payload(), bound2.Payload()) {
		t.Errorf("Binder method and alias produced disparate results")
	}
}

func TestBind_EmptyOrInvalidGrantRejection(t *testing.T) {
	attempt := executionattempt.ExecutionAttempt{
		OrganizationID:       uuid.New(),
		AgentID:              uuid.New(),
		GovernedActionID:     uuid.New(),
		GovernanceDecisionID: uuid.New(),
		ToolName:             "payments",
		OperationName:        "execute",
		Payload:              []byte(knownRawPayload),
	}

	// Zero-value grant
	emptyGrant := executiongrant.VerifiedExecutionGrant{}
	_, err := executionattempt.Bind(emptyGrant, attempt)
	if !errors.Is(err, executionattempt.ErrGrantBindingMismatch) {
		t.Errorf("expected ErrGrantBindingMismatch for zero-value grant, got: %v", err)
	}

	// Grant with Nil GrantID
	now := time.Now().UTC()
	grantWithNilID := validTestGrant(now)
	grantWithNilID.GrantID = uuid.Nil
	_, err = executionattempt.Bind(grantWithNilID, attempt)
	if !errors.Is(err, executionattempt.ErrGrantBindingMismatch) {
		t.Errorf("expected ErrGrantBindingMismatch for grant with Nil GrantID, got: %v", err)
	}
}

func TestBind_Concurrency(t *testing.T) {
	now := time.Now().UTC()
	grant := validTestGrant(now)
	attempt := validTestAttempt(grant)

	const goroutines = 50
	var wg sync.WaitGroup
	wg.Add(goroutines)

	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			bound, err := executionattempt.Bind(grant, attempt)
			if err != nil {
				t.Errorf("concurrent bind failed: %v", err)
				return
			}
			if bound.PayloadHash() != knownPayloadHash {
				t.Errorf("concurrent bind produced bad hash: %s", bound.PayloadHash())
			}
			p := bound.Payload()
			if len(p) == 0 {
				t.Errorf("concurrent bind returned empty payload")
			}
		}()
	}

	wg.Wait()
}
