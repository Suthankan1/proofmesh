package pep_test

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/lestrrat-go/jwx/v3/jwa"
	"github.com/lestrrat-go/jwx/v3/jws"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/canonicalize"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executionattempt"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
)

// --- Test Fakes & Mocks ---

type fakeVerifier struct {
	mu           sync.Mutex
	calls        int
	lastToken    string
	lastCtx      context.Context
	returnGrant  executiongrant.VerifiedExecutionGrant
	returnErr    error
	hookBeforeFn func()
}

func (f *fakeVerifier) Verify(ctx context.Context, compactToken string) (executiongrant.VerifiedExecutionGrant, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	f.lastToken = compactToken
	f.lastCtx = ctx
	if f.hookBeforeFn != nil {
		f.hookBeforeFn()
	}
	if f.returnErr != nil {
		return executiongrant.VerifiedExecutionGrant{}, f.returnErr
	}
	return f.returnGrant, nil
}

type recordingExecutor struct {
	mu          sync.Mutex
	calls       int
	lastCall    pep.BoundToolCall
	lastCtx     context.Context
	returnRes   pep.ToolResult
	returnErr   error
	capturedRaw []byte
}

func (r *recordingExecutor) Execute(ctx context.Context, call pep.BoundToolCall) (pep.ToolResult, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls++
	r.lastCall = call
	r.lastCtx = ctx
	r.capturedRaw = bytes.Clone(call.Payload())
	if r.returnErr != nil {
		return pep.ToolResult{}, r.returnErr
	}
	return r.returnRes, nil
}

type controllableClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *controllableClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *controllableClock) Set(t time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = t
}

func (c *controllableClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

type staticKeyResolver struct {
	key *ecdsa.PublicKey
	err error
}

func (r *staticKeyResolver) ResolveExecutionGrantKey(_ context.Context, _ string) (*ecdsa.PublicKey, error) {
	if r.err != nil {
		return nil, r.err
	}
	return r.key, nil
}

// --- Helper Functions ---

func newValidTestFixtures() (
	executiongrant.VerifiedExecutionGrant,
	executionattempt.ExecutionAttempt,
	[]byte, // raw payload
	[]byte, // canonical payload
	string, // canonical hash
) {
	orgID := uuid.New()
	agentID := uuid.New()
	actionID := uuid.New()
	decisionID := uuid.New()
	grantID := uuid.New()
	toolName := "payments"
	opName := "process_payment"

	rawPayload := []byte("{\n  \"paymentId\": \"pay_123\",\n  \"amount\": 5000\n}")
	canonicalPayload, computedHash, err := canonicalize.CanonicalizeAndHash(rawPayload)
	if err != nil {
		panic(err)
	}

	exp := time.Date(2026, 9, 5, 12, 0, 30, 0, time.UTC)

	grant := executiongrant.VerifiedExecutionGrant{
		GrantID:              grantID,
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decisionID,
		ToolName:             toolName,
		OperationName:        opName,
		PayloadHash:          computedHash,
		Issuer:               "https://controlplane.proofmesh.internal",
		Audience:             "proofmesh-gateway",
		IssuedAt:             exp.Add(-30 * time.Second),
		ExpiresAt:            exp,
	}

	attempt := executionattempt.ExecutionAttempt{
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decisionID,
		ToolName:             toolName,
		OperationName:        opName,
		Payload:              bytes.Clone(rawPayload),
	}

	return grant, attempt, rawPayload, canonicalPayload, computedHash
}

// --- Unit & Orchestration Tests ---

func TestNewEnforcer_Validation(t *testing.T) {
	t.Parallel()

	verifier := &fakeVerifier{}
	executor := &recordingExecutor{}
	clock := &controllableClock{now: time.Now().UTC()}

	tests := []struct {
		name        string
		verifier    pep.GrantVerifier
		executor    pep.ToolExecutor
		clock       executiongrant.Clock
		expectedErr error
	}{
		{
			name:        "nil verifier",
			verifier:    nil,
			executor:    executor,
			clock:       clock,
			expectedErr: pep.ErrNilVerifier,
		},
		{
			name:        "nil executor",
			verifier:    verifier,
			executor:    nil,
			clock:       clock,
			expectedErr: pep.ErrNilExecutor,
		},
		{
			name:        "nil clock",
			verifier:    verifier,
			executor:    executor,
			clock:       nil,
			expectedErr: pep.ErrNilClock,
		},
		{
			name:        "valid dependencies",
			verifier:    verifier,
			executor:    executor,
			clock:       clock,
			expectedErr: nil,
		},
	}

	for _, tc := range tests {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			enforcer, err := pep.NewEnforcer(tc.verifier, tc.executor, tc.clock)
			if tc.expectedErr != nil {
				if !errors.Is(err, tc.expectedErr) {
					t.Fatalf("expected error %v, got %v", tc.expectedErr, err)
				}
				if enforcer != nil {
					t.Fatal("expected nil enforcer on constructor failure")
				}
			} else {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if enforcer == nil {
					t.Fatal("expected non-nil enforcer")
				}
			}
		})
	}
}

func TestEnforcer_Execute_NilReceiverOrUninitialized(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	attempt := executionattempt.ExecutionAttempt{}

	var nilEnforcer *pep.Enforcer
	_, err := nilEnforcer.Execute(ctx, "token", attempt)
	if !errors.Is(err, pep.ErrInvalidEnforcer) {
		t.Fatalf("expected ErrInvalidEnforcer on nil receiver, got %v", err)
	}

	uninitialized := &pep.Enforcer{}
	_, err = uninitialized.Execute(ctx, "token", attempt)
	if !errors.Is(err, pep.ErrInvalidEnforcer) {
		t.Fatalf("expected ErrInvalidEnforcer on uninitialized enforcer, got %v", err)
	}
}

func TestEnforcer_Execute_PositivePath(t *testing.T) {
	t.Parallel()

	grant, attempt, _, canonicalPayload, _ := newValidTestFixtures()
	clock := &controllableClock{now: grant.ExpiresAt.Add(-10 * time.Second)}

	verifier := &fakeVerifier{returnGrant: grant}
	executor := &recordingExecutor{
		returnRes: pep.ToolResult{Payload: []byte(`{"status":"payment_processed"}`)},
	}

	enforcer, err := pep.NewEnforcer(verifier, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	ctx := context.WithValue(context.Background(), struct{ key string }{"trace"}, "test-trace-123")
	compactToken := "header.payload.signature"

	res, err := enforcer.Execute(ctx, compactToken, attempt)
	if err != nil {
		t.Fatalf("Execute failed unexpectedly: %v", err)
	}

	// Invariants:
	// 1. Verifier called exactly once with exact token and context
	if verifier.calls != 1 {
		t.Fatalf("expected verifier calls == 1, got %d", verifier.calls)
	}
	if verifier.lastToken != compactToken {
		t.Fatalf("expected verifier token %q, got %q", compactToken, verifier.lastToken)
	}
	if verifier.lastCtx != ctx {
		t.Fatal("expected caller ctx passed to verifier")
	}

	// 2. Executor called exactly once with exact context
	if executor.calls != 1 {
		t.Fatalf("expected executor calls == 1, got %d", executor.calls)
	}
	if executor.lastCtx != ctx {
		t.Fatal("expected caller ctx passed to executor")
	}

	// 3. Executor received metadata strictly from verified grant / bound object
	call := executor.lastCall
	if call.GrantID() != grant.GrantID {
		t.Errorf("GrantID mismatch: got %s, want %s", call.GrantID(), grant.GrantID)
	}
	if call.OrganizationID() != grant.OrganizationID {
		t.Errorf("OrganizationID mismatch: got %s, want %s", call.OrganizationID(), grant.OrganizationID)
	}
	if call.AgentID() != grant.AgentID {
		t.Errorf("AgentID mismatch: got %s, want %s", call.AgentID(), grant.AgentID)
	}
	if call.GovernedActionID() != grant.GovernedActionID {
		t.Errorf("GovernedActionID mismatch: got %s, want %s", call.GovernedActionID(), grant.GovernedActionID)
	}
	if call.GovernanceDecisionID() != grant.GovernanceDecisionID {
		t.Errorf("GovernanceDecisionID mismatch: got %s, want %s", call.GovernanceDecisionID(), grant.GovernanceDecisionID)
	}
	if call.ToolName() != grant.ToolName {
		t.Errorf("ToolName mismatch: got %s, want %s", call.ToolName(), grant.ToolName)
	}
	if call.OperationName() != grant.OperationName {
		t.Errorf("OperationName mismatch: got %s, want %s", call.OperationName(), grant.OperationName)
	}
	if call.PayloadHash() != grant.PayloadHash {
		t.Errorf("PayloadHash mismatch: got %s, want %s", call.PayloadHash(), grant.PayloadHash)
	}
	if !call.ExpiresAt().Equal(grant.ExpiresAt) {
		t.Errorf("ExpiresAt mismatch: got %s, want %s", call.ExpiresAt(), grant.ExpiresAt)
	}

	// 4. Executor received RFC 8785 canonical payload bytes (not unformatted attempt payload)
	if !bytes.Equal(call.Payload(), canonicalPayload) {
		t.Fatalf("expected canonical payload %s, got %s", canonicalPayload, call.Payload())
	}

	// 5. Result payload propagated accurately
	expectedRes := []byte(`{"status":"payment_processed"}`)
	if !bytes.Equal(res.Payload, expectedRes) {
		t.Fatalf("expected result %s, got %s", expectedRes, res.Payload)
	}
}

func TestEnforcer_Execute_CanonicalPayloadPreservedDespiteAttemptMutation(t *testing.T) {
	t.Parallel()

	grant, attempt, _, canonicalPayload, _ := newValidTestFixtures()
	clock := &controllableClock{now: grant.ExpiresAt.Add(-10 * time.Second)}

	verifier := &fakeVerifier{returnGrant: grant}
	executor := &recordingExecutor{
		returnRes: pep.ToolResult{Payload: []byte(`{"ok":true}`)},
	}

	enforcer, err := pep.NewEnforcer(verifier, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	// Execute
	_, err = enforcer.Execute(context.Background(), "token", attempt)
	if err != nil {
		t.Fatalf("Execute failed: %v", err)
	}

	// Verify executor received strictly canonical payload
	if !bytes.Equal(executor.capturedRaw, canonicalPayload) {
		t.Fatalf("executor did not receive canonical payload: got %s, want %s", executor.capturedRaw, canonicalPayload)
	}

	// Mutate attempt payload and prove executor call was not tainted
	attempt.Payload[0] = 'X'
	if bytes.Equal(executor.capturedRaw, attempt.Payload) {
		t.Fatal("executor captured payload unexpectedly matched mutated attempt slice")
	}
}

func TestBoundToolCall_PayloadDefensiveCopy(t *testing.T) {
	t.Parallel()

	grant, attempt, _, canonicalPayload, _ := newValidTestFixtures()
	clock := &controllableClock{now: grant.ExpiresAt.Add(-10 * time.Second)}

	verifier := &fakeVerifier{returnGrant: grant}
	executor := &recordingExecutor{}

	enforcer, err := pep.NewEnforcer(verifier, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	_, err = enforcer.Execute(context.Background(), "token", attempt)
	if err != nil {
		t.Fatalf("Execute failed: %v", err)
	}

	call := executor.lastCall

	// Mutate the returned payload slice
	p1 := call.Payload()
	p1[0] = 'Z'

	// Re-read payload from call
	p2 := call.Payload()
	if bytes.Equal(p1, p2) {
		t.Fatal("BoundToolCall.Payload() does not return a defensive copy")
	}
	if !bytes.Equal(p2, canonicalPayload) {
		t.Fatalf("internal call payload was corrupted: got %s, want %s", p2, canonicalPayload)
	}

	// Check safe String() redacts payload
	str := call.String()
	if strings.Contains(str, "paymentId") || strings.Contains(str, "5000") {
		t.Fatalf("BoundToolCall.String() leaked raw payload: %s", str)
	}
}

func TestEnforcer_Execute_SanitizesExecutorFailure(t *testing.T) {
	t.Parallel()

	grant, attempt, _, _, _ := newValidTestFixtures()
	clock := &controllableClock{now: grant.ExpiresAt.Add(-10 * time.Second)}

	verifier := &fakeVerifier{returnGrant: grant}
	sensitiveCanary := "canary-db-timeout: sensitive internal host 10.10.10.5:5432"
	executor := &recordingExecutor{
		returnErr: errors.New(sensitiveCanary),
	}

	enforcer, err := pep.NewEnforcer(verifier, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	res, err := enforcer.Execute(context.Background(), "token", attempt)
	if !errors.Is(err, pep.ErrToolExecutionFailed) {
		t.Fatalf("expected ErrToolExecutionFailed, got %v", err)
	}
	if strings.Contains(err.Error(), sensitiveCanary) {
		t.Fatalf("raw executor error leaked through PEP boundary: %v", err)
	}
	if len(res.Payload) != 0 {
		t.Fatalf("expected empty result payload on executor failure, got %s", res.Payload)
	}
	if executor.calls != 1 {
		t.Fatalf("expected exactly 1 executor call, got %d", executor.calls)
	}
}

func TestEnforcer_Execute_ExecutionBoundaryExpiryChecks(t *testing.T) {
	t.Parallel()

	grant, attempt, _, _, _ := newValidTestFixtures()
	exp := grant.ExpiresAt

	tests := []struct {
		name          string
		now           time.Time
		expectedErr   error
		expectExecute bool
	}{
		{
			name:          "strictly before expiry (now < exp)",
			now:           exp.Add(-1 * time.Nanosecond),
			expectedErr:   nil,
			expectExecute: true,
		},
		{
			name:          "exactly at expiry (now == exp) must reject",
			now:           exp,
			expectedErr:   pep.ErrExecutionGrantExpired,
			expectExecute: false,
		},
		{
			name:          "strictly after expiry (now > exp)",
			now:           exp.Add(1 * time.Nanosecond),
			expectedErr:   pep.ErrExecutionGrantExpired,
			expectExecute: false,
		},
		{
			name:          "well after expiry",
			now:           exp.Add(10 * time.Minute),
			expectedErr:   pep.ErrExecutionGrantExpired,
			expectExecute: false,
		},
	}

	for _, tc := range tests {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			clock := &controllableClock{now: tc.now}
			verifier := &fakeVerifier{returnGrant: grant}
			executor := &recordingExecutor{
				returnRes: pep.ToolResult{Payload: []byte(`{"ok":true}`)},
			}

			enforcer, err := pep.NewEnforcer(verifier, executor, clock)
			if err != nil {
				t.Fatalf("NewEnforcer failed: %v", err)
			}

			_, err = enforcer.Execute(context.Background(), "token", attempt)
			if tc.expectedErr != nil {
				if !errors.Is(err, tc.expectedErr) {
					t.Fatalf("expected error %v, got %v", tc.expectedErr, err)
				}
				if executor.calls != 0 {
					t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION: executor called %d times on expired grant", executor.calls)
				}
			} else {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if executor.calls != 1 {
					t.Fatalf("expected executor called once, got %d", executor.calls)
				}
			}
		})
	}
}

func TestEnforcer_Execute_TimeProgressionExpiresBetweenVerifyAndExecute(t *testing.T) {
	t.Parallel()

	grant, attempt, _, _, _ := newValidTestFixtures()
	exp := grant.ExpiresAt

	// Start clock 5 seconds before expiry
	clock := &controllableClock{now: exp.Add(-5 * time.Second)}

	verifier := &fakeVerifier{
		returnGrant: grant,
		hookBeforeFn: func() {
			// Advance clock to exactly ExpiresAt during verify
			clock.Set(exp)
		},
	}
	executor := &recordingExecutor{}

	enforcer, err := pep.NewEnforcer(verifier, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	_, err = enforcer.Execute(context.Background(), "token", attempt)
	if !errors.Is(err, pep.ErrExecutionGrantExpired) {
		t.Fatalf("expected ErrExecutionGrantExpired when clock reached ExpiresAt before execution boundary, got %v", err)
	}

	if verifier.calls != 1 {
		t.Fatalf("expected verifier called once, got %d", verifier.calls)
	}
	if executor.calls != 0 {
		t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION: executor called %d times despite grant expiry at execution boundary", executor.calls)
	}
}

func TestEnforcer_Execute_SanityCheck_RejectsZeroOrPartialBoundObject(t *testing.T) {
	t.Parallel()

	grant, attempt, _, _, _ := newValidTestFixtures()
	clock := &controllableClock{now: grant.ExpiresAt.Add(-10 * time.Second)}

	// A grant with zero ExpiresAt passes executionattempt.Bind, but must fail PEP bound-object sanity
	corruptedGrant := grant
	corruptedGrant.ExpiresAt = time.Time{}

	verifier := &fakeVerifier{returnGrant: corruptedGrant}
	executor := &recordingExecutor{}

	enforcer, err := pep.NewEnforcer(verifier, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	_, err = enforcer.Execute(context.Background(), "token", attempt)
	if !errors.Is(err, pep.ErrInvalidBoundAttempt) {
		t.Fatalf("expected ErrInvalidBoundAttempt for zero ExpiresAt bound object, got %v", err)
	}
	if executor.calls != 0 {
		t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION: executor called %d times on invalid bound object", executor.calls)
	}
}

func TestEnforcer_Execute_ComprehensiveFailureMatrix_ExecutorNeverCalled(t *testing.T) {
	t.Parallel()

	validGrant, validAttempt, _, _, _ := newValidTestFixtures()
	clockTime := validGrant.ExpiresAt.Add(-10 * time.Second)

	type testCase struct {
		name        string
		token       string
		setupGrant  func() (executiongrant.VerifiedExecutionGrant, error)
		setupAtt    func() executionattempt.ExecutionAttempt
		setupClock  func() time.Time
		expectedErr error
	}

	cases := []testCase{
		{
			name:  "blank/malformed token -> verifier failure",
			token: "   ",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return executiongrant.VerifiedExecutionGrant{}, errors.New("malformed token")
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				return validAttempt
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrVerificationFailed,
		},
		{
			name:  "unknown kid -> verifier failure",
			token: "token.with.unknown-kid",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return executiongrant.VerifiedExecutionGrant{}, errors.New("key not found for kid")
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				return validAttempt
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrVerificationFailed,
		},
		{
			name:  "bad signature -> verifier failure",
			token: "token.with.bad-sig",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return executiongrant.VerifiedExecutionGrant{}, errors.New("signature verification failed")
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				return validAttempt
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrVerificationFailed,
		},
		{
			name:  "expired token -> verifier failure",
			token: "token.expired",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return executiongrant.VerifiedExecutionGrant{}, executiongrant.ErrTokenExpired
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				return validAttempt
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrVerificationFailed,
		},
		{
			name:  "organization mismatch",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.OrganizationID = uuid.New()
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "agent mismatch",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.AgentID = uuid.New()
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "action mismatch",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.GovernedActionID = uuid.New()
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "decision mismatch",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.GovernanceDecisionID = uuid.New()
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "tool mismatch",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.ToolName = "different_tool"
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "operation mismatch",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.OperationName = "different_op"
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "payload mismatch (different amount)",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.Payload = []byte(`{"paymentId":"pay_123","amount":9999}`)
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "invalid payload (malformed JSON)",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.Payload = []byte(`{not-json}`)
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "invalid payload (duplicate keys)",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				att := validAttempt
				att.Payload = []byte(`{"a":1,"a":2}`)
				return att
			},
			setupClock:  func() time.Time { return clockTime },
			expectedErr: pep.ErrBindingFailed,
		},
		{
			name:  "execution-boundary exact expiry (now == exp)",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				return validAttempt
			},
			setupClock:  func() time.Time { return validGrant.ExpiresAt },
			expectedErr: pep.ErrExecutionGrantExpired,
		},
		{
			name:  "execution-boundary past expiry (now > exp)",
			token: "valid.token",
			setupGrant: func() (executiongrant.VerifiedExecutionGrant, error) {
				return validGrant, nil
			},
			setupAtt: func() executionattempt.ExecutionAttempt {
				return validAttempt
			},
			setupClock:  func() time.Time { return validGrant.ExpiresAt.Add(1 * time.Second) },
			expectedErr: pep.ErrExecutionGrantExpired,
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			grant, verifierErr := tc.setupGrant()
			verifier := &fakeVerifier{
				returnGrant: grant,
				returnErr:   verifierErr,
			}
			executor := &recordingExecutor{}
			clock := &controllableClock{now: tc.setupClock()}

			enforcer, err := pep.NewEnforcer(verifier, executor, clock)
			if err != nil {
				t.Fatalf("NewEnforcer failed: %v", err)
			}

			att := tc.setupAtt()
			_, err = enforcer.Execute(context.Background(), tc.token, att)
			if !errors.Is(err, tc.expectedErr) {
				t.Fatalf("expected error %v, got %v", tc.expectedErr, err)
			}

			// Core Fail-Closed Invariant: Executor call count MUST BE ZERO
			if executor.calls != 0 {
				t.Fatalf("FAIL-CLOSED INVARIANT VIOLATION in %q: executor.calls == %d, expected 0", tc.name, executor.calls)
			}
		})
	}
}

func TestEnforcer_Execute_ContextCancellation(t *testing.T) {
	t.Parallel()

	grant, attempt, _, _, _ := newValidTestFixtures()
	clock := &controllableClock{now: grant.ExpiresAt.Add(-10 * time.Second)}

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // pre-cancel context

	verifier := &fakeVerifier{
		returnGrant: grant,
		returnErr:   ctx.Err(),
	}
	executor := &recordingExecutor{}

	enforcer, err := pep.NewEnforcer(verifier, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	_, err = enforcer.Execute(ctx, "token", attempt)
	if !errors.Is(err, pep.ErrVerificationFailed) {
		t.Fatalf("expected ErrVerificationFailed on canceled context, got %v", err)
	}
	if executor.calls != 0 {
		t.Fatalf("executor should not be called when verifier fails on canceled context, calls=%d", executor.calls)
	}
}

// --- End-to-End Integration with Real Verifier ---

func TestEnforcer_Execute_EndToEndWithRealVerifier(t *testing.T) {
	t.Parallel()

	ctx := context.Background()

	// 1. Generate P-256 ECDSA key pair
	privKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey failed: %v", err)
	}

	keyID := "gateway-pep-key-1"
	resolver := &staticKeyResolver{key: &privKey.PublicKey}

	issuer := "https://controlplane.proofmesh.internal"
	audience := "proofmesh-gateway"
	now := time.Date(2026, 9, 5, 12, 0, 0, 0, time.UTC)
	clock := &controllableClock{now: now}

	// 2. Construct production Verifier from internal/executiongrant
	realVerifier, err := executiongrant.NewVerifier(issuer, audience, clock, resolver)
	if err != nil {
		t.Fatalf("NewVerifier failed: %v", err)
	}

	// 3. Construct test fixtures
	orgID := uuid.New()
	agentID := uuid.New()
	actionID := uuid.New()
	decisionID := uuid.New()
	grantID := uuid.New()
	toolName := "database_backup"
	opName := "snapshot"

	rawPayload := []byte(`{  "snapshotName": "backup_01", "retainDays": 7}`)
	_, computedHash, err := canonicalize.CanonicalizeAndHash(rawPayload)
	if err != nil {
		t.Fatalf("CanonicalizeAndHash failed: %v", err)
	}

	// 4. Build JSON claims payload
	claims := map[string]any{
		"iss":            issuer,
		"aud":            audience,
		"jti":            grantID.String(),
		"iat":            now.Unix(),
		"exp":            now.Add(30 * time.Second).Unix(),
		"org_id":         orgID.String(),
		"agent_id":       agentID.String(),
		"action_id":      actionID.String(),
		"decision_id":    decisionID.String(),
		"tool_name":      toolName,
		"operation_name": opName,
		"payload_hash":   computedHash,
	}
	claimsBytes, err := json.Marshal(claims)
	if err != nil {
		t.Fatalf("json.Marshal failed: %v", err)
	}

	// 5. Sign valid ES256 JWS with exact typ and kid headers
	headers := jws.NewHeaders()
	if err := headers.Set("typ", executiongrant.ExpectedGrantType); err != nil {
		t.Fatalf("Set typ failed: %v", err)
	}
	if err := headers.Set("kid", keyID); err != nil {
		t.Fatalf("Set kid failed: %v", err)
	}

	signedBytes, err := jws.Sign(claimsBytes, jws.WithKey(jwa.ES256(), privKey, jws.WithProtectedHeaders(headers)))
	if err != nil {
		t.Fatalf("jws.Sign failed: %v", err)
	}
	compactToken := string(signedBytes)

	// 6. Build Enforcer with real verifier and recording executor
	executor := &recordingExecutor{
		returnRes: pep.ToolResult{Payload: []byte(`{"snapshot_id":"snap_999"}`)},
	}

	enforcer, err := pep.NewEnforcer(realVerifier, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	attempt := executionattempt.ExecutionAttempt{
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decisionID,
		ToolName:             toolName,
		OperationName:        opName,
		Payload:              rawPayload,
	}

	// 7. Execute through PEP
	res, err := enforcer.Execute(ctx, compactToken, attempt)
	if err != nil {
		t.Fatalf("Execute failed with real verifier: %v", err)
	}

	if executor.calls != 1 {
		t.Fatalf("expected executor called once, got %d", executor.calls)
	}

	call := executor.lastCall
	if call.GrantID() != grantID {
		t.Errorf("GrantID mismatch: got %s, want %s", call.GrantID(), grantID)
	}
	if call.ToolName() != toolName {
		t.Errorf("ToolName mismatch: got %s, want %s", call.ToolName(), toolName)
	}
	if call.PayloadHash() != computedHash {
		t.Errorf("PayloadHash mismatch: got %s, want %s", call.PayloadHash(), computedHash)
	}

	expectedResult := []byte(`{"snapshot_id":"snap_999"}`)
	if !bytes.Equal(res.Payload, expectedResult) {
		t.Fatalf("expected result %s, got %s", expectedResult, res.Payload)
	}
}
