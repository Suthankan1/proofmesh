package httpexecutor_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/canonicalize"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executionattempt"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
	httpexecutor "github.com/Suthankan1/proofmesh/apps/gateway/internal/toolexecutor/http"
)

// --- Test Fakes for PEP Integration ---

type testFixedClock struct {
	now time.Time
}

func (c *testFixedClock) Now() time.Time {
	return c.now
}

type testFakeVerifier struct {
	grant executiongrant.VerifiedExecutionGrant
}

func (v *testFakeVerifier) Verify(_ context.Context, _ string) (executiongrant.VerifiedExecutionGrant, error) {
	return v.grant, nil
}

type testFakeAuthority struct{}

func (a *testFakeAuthority) Claim(_ context.Context, _ pep.BoundToolCall) (pep.ClaimResult, error) {
	return pep.ClaimAcquired, nil
}

type testCapturingExecutor struct {
	captured pep.BoundToolCall
}

func (c *testCapturingExecutor) Execute(_ context.Context, call pep.BoundToolCall) (pep.ToolResult, error) {
	c.captured = call
	return pep.ToolResult{}, nil
}

// helper to construct a verified pep.BoundToolCall via standard PEP binding pipeline
func makeBoundCall(t *testing.T, toolName, opName string, rawPayload []byte) pep.BoundToolCall {
	t.Helper()

	canonicalPayload, computedHash, err := canonicalize.CanonicalizeAndHash(rawPayload)
	if err != nil {
		t.Fatalf("canonicalize failed: %v", err)
	}

	grant := executiongrant.VerifiedExecutionGrant{
		GrantID:              uuid.New(),
		OrganizationID:       uuid.New(),
		AgentID:              uuid.New(),
		GovernedActionID:     uuid.New(),
		GovernanceDecisionID: uuid.New(),
		ToolName:             toolName,
		OperationName:        opName,
		PayloadHash:          computedHash,
		ExpiresAt:            time.Now().Add(10 * time.Minute).UTC(),
		Issuer:               "https://controlplane.proofmesh.internal",
		Audience:             "proofmesh-gateway",
	}

	attempt := executionattempt.ExecutionAttempt{
		OrganizationID:       grant.OrganizationID,
		AgentID:              grant.AgentID,
		GovernedActionID:     grant.GovernedActionID,
		GovernanceDecisionID: grant.GovernanceDecisionID,
		ToolName:             toolName,
		OperationName:        opName,
		Payload:              canonicalPayload,
	}

	capture := &testCapturingExecutor{}
	enforcer, err := pep.NewEnforcer(
		&testFakeVerifier{grant: grant},
		&testFakeAuthority{},
		capture,
		&testFixedClock{now: time.Now().UTC()},
	)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	_, err = enforcer.Execute(context.Background(), "valid-token", attempt)
	if err != nil {
		t.Fatalf("enforcer.Execute failed: %v", err)
	}

	return capture.captured
}

// --- Tests ---

// 1. Positive integration test driven through real pep.Enforcer
func TestExecutor_PositiveIntegration_WithPEPEnforcer(t *testing.T) {
	t.Parallel()

	var receivedMethod string
	var receivedPath string
	var receivedQuery string
	var receivedContentType string
	var receivedAccept string
	var receivedAuth string
	var receivedCookie string
	var receivedGrantHeader string
	var receivedBody []byte
	var requestCount int32

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&requestCount, 1)
		receivedMethod = r.Method
		receivedPath = r.URL.Path
		receivedQuery = r.URL.RawQuery
		receivedContentType = r.Header.Get("Content-Type")
		receivedAccept = r.Header.Get("Accept")
		receivedAuth = r.Header.Get("Authorization")
		receivedCookie = r.Header.Get("Cookie")
		receivedGrantHeader = r.Header.Get("X-Execution-Grant")

		body, err := io.ReadAll(r.Body)
		if err == nil {
			receivedBody = body
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"payment_posted","id":"tx_999"}`))
	}))
	defer server.Close()

	toolName := "payments"
	opName := "execute"
	endpoint := server.URL + "/v1/settle?currency=USD"

	targets := []httpexecutor.Target{
		{
			ToolName:      toolName,
			OperationName: opName,
			URL:           endpoint,
		},
	}

	executor, err := httpexecutor.NewExecutor(targets, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	rawPayload := []byte(`{"paymentId":"pay_123","amount":5000}`)
	canonicalPayload, computedHash, err := canonicalize.CanonicalizeAndHash(rawPayload)
	if err != nil {
		t.Fatalf("canonicalize failed: %v", err)
	}

	grant := executiongrant.VerifiedExecutionGrant{
		GrantID:              uuid.New(),
		OrganizationID:       uuid.New(),
		AgentID:              uuid.New(),
		GovernedActionID:     uuid.New(),
		GovernanceDecisionID: uuid.New(),
		ToolName:             toolName,
		OperationName:        opName,
		PayloadHash:          computedHash,
		ExpiresAt:            time.Now().Add(5 * time.Minute).UTC(),
		Issuer:               "https://controlplane.proofmesh.internal",
		Audience:             "proofmesh-gateway",
	}

	attempt := executionattempt.ExecutionAttempt{
		OrganizationID:       grant.OrganizationID,
		AgentID:              grant.AgentID,
		GovernedActionID:     grant.GovernedActionID,
		GovernanceDecisionID: grant.GovernanceDecisionID,
		ToolName:             toolName,
		OperationName:        opName,
		Payload:              rawPayload,
	}

	clock := &testFixedClock{now: time.Now().UTC()}
	enforcer, err := pep.NewEnforcer(&testFakeVerifier{grant: grant}, &testFakeAuthority{}, executor, clock)
	if err != nil {
		t.Fatalf("NewEnforcer failed: %v", err)
	}

	res, err := enforcer.Execute(context.Background(), "simulated-token", attempt)
	if err != nil {
		t.Fatalf("enforcer.Execute failed: %v", err)
	}

	if atomic.LoadInt32(&requestCount) != 1 {
		t.Fatalf("expected 1 server request, got %d", requestCount)
	}
	if receivedMethod != http.MethodPost {
		t.Fatalf("expected POST, got %s", receivedMethod)
	}
	if receivedPath != "/v1/settle" {
		t.Fatalf("expected path /v1/settle, got %s", receivedPath)
	}
	if receivedQuery != "currency=USD" {
		t.Fatalf("expected query currency=USD, got %s", receivedQuery)
	}
	if receivedContentType != "application/json" {
		t.Fatalf("expected Content-Type application/json, got %s", receivedContentType)
	}
	if receivedAccept != "application/json" {
		t.Fatalf("expected Accept application/json, got %s", receivedAccept)
	}
	if receivedAuth != "" {
		t.Fatalf("expected empty Authorization header, got %s", receivedAuth)
	}
	if receivedCookie != "" {
		t.Fatalf("expected empty Cookie header, got %s", receivedCookie)
	}
	if receivedGrantHeader != "" {
		t.Fatalf("expected empty X-Execution-Grant header, got %s", receivedGrantHeader)
	}
	if !bytes.Equal(receivedBody, canonicalPayload) {
		t.Fatalf("expected canonical payload %s, got %s", string(canonicalPayload), string(receivedBody))
	}
	expectedOutput := []byte(`{"status":"payment_posted","id":"tx_999"}`)
	if !bytes.Equal(res.Payload, expectedOutput) {
		t.Fatalf("expected result payload %s, got %s", string(expectedOutput), string(res.Payload))
	}
}

// 2. Canonical payload exactness (proves no JSON remarshaling or re-ordering)
func TestExecutor_CanonicalPayloadExactness(t *testing.T) {
	t.Parallel()

	var receivedBody []byte
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		receivedBody = body
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer server.Close()

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "ledger", OperationName: "append", URL: server.URL},
	}, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	// Uncanonical JSON input: whitespace and reverse alphabetical order
	rawAttemptJSON := []byte(`{   "paymentId" :  "pay_123"  ,  "amount" :  5000   }`)
	boundCall := makeBoundCall(t, "ledger", "append", rawAttemptJSON)

	// RFC 8785 canonical bytes must be {"amount":5000,"paymentId":"pay_123"}
	expectedCanonical := []byte(`{"amount":5000,"paymentId":"pay_123"}`)

	res, err := executor.Execute(context.Background(), boundCall)
	if err != nil {
		t.Fatalf("Execute failed: %v", err)
	}
	if !bytes.Equal(receivedBody, expectedCanonical) {
		t.Fatalf("outbound body altered! Expected %s, got %s", string(expectedCanonical), string(receivedBody))
	}
	if !bytes.Equal(res.Payload, []byte(`{"ok":true}`)) {
		t.Fatalf("unexpected response payload: %s", string(res.Payload))
	}
}

// 3. Exact routing: case and whitespace sensitivity
func TestExecutor_ExactRouting_CaseAndWhitespaceSensitivity(t *testing.T) {
	t.Parallel()

	var requestCount int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&requestCount, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "payments", OperationName: "execute", URL: server.URL},
	}, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	payload := []byte(`{"id":"1"}`)
	testCases := []struct {
		name string
		tool string
		op   string
	}{
		{name: "case mismatch tool", tool: "Payments", op: "execute"},
		{name: "case mismatch op", tool: "payments", op: "Execute"},
		{name: "trailing space tool", tool: "payments ", op: "execute"},
		{name: "trailing space op", tool: "payments", op: "execute "},
		{name: "leading space tool", tool: " payments", op: "execute"},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			call := makeBoundCall(t, tc.tool, tc.op, payload)
			_, err := executor.Execute(context.Background(), call)
			if !errors.Is(err, httpexecutor.ErrUnknownTarget) {
				t.Fatalf("expected ErrUnknownTarget, got %v", err)
			}
		})
	}

	if atomic.LoadInt32(&requestCount) != 0 {
		t.Fatalf("expected 0 requests for mismatched routing, got %d", requestCount)
	}
}

// 4. Unknown target fails closed with zero network requests
func TestExecutor_UnknownTarget(t *testing.T) {
	t.Parallel()

	var requestCount int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&requestCount, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "payments", OperationName: "execute", URL: server.URL},
	}, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	call := makeBoundCall(t, "other_tool", "other_op", []byte(`{"a":1}`))
	_, err = executor.Execute(context.Background(), call)
	if !errors.Is(err, httpexecutor.ErrUnknownTarget) {
		t.Fatalf("expected ErrUnknownTarget, got %v", err)
	}
	if atomic.LoadInt32(&requestCount) != 0 {
		t.Fatalf("expected 0 requests, got %d", requestCount)
	}
}

// 5. Duplicate target key fails constructor
func TestExecutor_DuplicateTarget(t *testing.T) {
	t.Parallel()

	targets := []httpexecutor.Target{
		{ToolName: "tool1", OperationName: "op1", URL: "http://example.com/a"},
		{ToolName: "tool1", OperationName: "op1", URL: "http://example.com/b"},
	}

	_, err := httpexecutor.NewExecutor(targets, nil, httpexecutor.Policy{})
	if !errors.Is(err, httpexecutor.ErrDuplicateTarget) {
		t.Fatalf("expected ErrDuplicateTarget, got %v", err)
	}
}

// 6. Caller target mutation does not change routing
func TestExecutor_CallerTargetMutation(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer server.Close()

	targets := []httpexecutor.Target{
		{ToolName: "original_tool", OperationName: "original_op", URL: server.URL},
	}

	executor, err := httpexecutor.NewExecutor(targets, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	// Mutate caller slice after construction
	targets[0].ToolName = "mutated_tool"
	targets[0].OperationName = "mutated_op"
	targets[0].URL = "http://127.0.0.1:9999/mutated"

	// Original routing must still work
	call := makeBoundCall(t, "original_tool", "original_op", []byte(`{"k":"v"}`))
	res, err := executor.Execute(context.Background(), call)
	if err != nil {
		t.Fatalf("Execute failed on original target: %v", err)
	}
	if !bytes.Equal(res.Payload, []byte(`{"ok":true}`)) {
		t.Fatalf("unexpected payload: %s", string(res.Payload))
	}

	// Mutated target must be unknown
	mutatedCall := makeBoundCall(t, "mutated_tool", "mutated_op", []byte(`{"k":"v"}`))
	_, err = executor.Execute(context.Background(), mutatedCall)
	if !errors.Is(err, httpexecutor.ErrUnknownTarget) {
		t.Fatalf("expected ErrUnknownTarget for mutated target, got %v", err)
	}
}

// 7. Redirects are not followed
func TestExecutor_RedirectNotFollowed(t *testing.T) {
	t.Parallel()

	var serverBRequests int32
	serverB := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&serverBRequests, 1)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"server":"B"}`))
	}))
	defer serverB.Close()

	var serverARequests int32
	serverA := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&serverARequests, 1)
		http.Redirect(w, r, serverB.URL, http.StatusFound)
	}))
	defer serverA.Close()

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "redirect_tool", OperationName: "exec", URL: serverA.URL},
	}, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	call := makeBoundCall(t, "redirect_tool", "exec", []byte(`{"redirect":true}`))
	_, err = executor.Execute(context.Background(), call)
	if !errors.Is(err, httpexecutor.ErrUnexpectedStatus) {
		t.Fatalf("expected ErrUnexpectedStatus, got %v", err)
	}

	if atomic.LoadInt32(&serverARequests) != 1 {
		t.Fatalf("expected Server A to receive 1 request, got %d", serverARequests)
	}
	if atomic.LoadInt32(&serverBRequests) != 0 {
		t.Fatalf("security violation! Server B received %d requests through redirect", serverBRequests)
	}
}

// 8. No-retry policy on downstream 500 and transport failure
func TestExecutor_NoRetries_On500AndTransportError(t *testing.T) {
	t.Parallel()

	t.Run("server 500 issues exactly one request", func(t *testing.T) {
		var count int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&count, 1)
			w.WriteHeader(http.StatusInternalServerError)
			_, _ = w.Write([]byte(`{"error":"internal failure"}`))
		}))
		defer server.Close()

		executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
			{ToolName: "err_tool", OperationName: "run", URL: server.URL},
		}, nil, httpexecutor.Policy{})
		if err != nil {
			t.Fatalf("NewExecutor failed: %v", err)
		}

		call := makeBoundCall(t, "err_tool", "run", []byte(`{"x":1}`))
		_, err = executor.Execute(context.Background(), call)
		if !errors.Is(err, httpexecutor.ErrUnexpectedStatus) {
			t.Fatalf("expected ErrUnexpectedStatus, got %v", err)
		}
		if atomic.LoadInt32(&count) != 1 {
			t.Fatalf("expected exactly 1 request, got %d", count)
		}
	})

	t.Run("transport error issues exactly one round trip", func(t *testing.T) {
		var roundTripCount int32
		customTransport := roundTripFunc(func(r *http.Request) (*http.Response, error) {
			atomic.AddInt32(&roundTripCount, 1)
			return nil, errors.New("simulated dial failure")
		})

		executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
			{ToolName: "err_tool", OperationName: "run", URL: "http://example.internal/test"},
		}, customTransport, httpexecutor.Policy{})
		if err != nil {
			t.Fatalf("NewExecutor failed: %v", err)
		}

		call := makeBoundCall(t, "err_tool", "run", []byte(`{"x":1}`))
		_, err = executor.Execute(context.Background(), call)
		if !errors.Is(err, httpexecutor.ErrRequestFailed) {
			t.Fatalf("expected ErrRequestFailed, got %v", err)
		}
		if atomic.LoadInt32(&roundTripCount) != 1 {
			t.Fatalf("expected exactly 1 round trip, got %d", roundTripCount)
		}
	})
}

// 9. Timeout enforcement
func TestExecutor_Timeout(t *testing.T) {
	t.Parallel()

	var reqCount int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&reqCount, 1)
		time.Sleep(150 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	policy := httpexecutor.Policy{
		RequestTimeout:   30 * time.Millisecond,
		MaxResponseBytes: 1024,
	}

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "slow_tool", OperationName: "wait", URL: server.URL},
	}, nil, policy)
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	call := makeBoundCall(t, "slow_tool", "wait", []byte(`{"sleep":true}`))
	_, err = executor.Execute(context.Background(), call)
	if !errors.Is(err, httpexecutor.ErrRequestFailed) {
		t.Fatalf("expected ErrRequestFailed on timeout, got %v", err)
	}
	if atomic.LoadInt32(&reqCount) != 1 {
		t.Fatalf("expected 1 request attempt, got %d", reqCount)
	}
}

// 10. Oversized response fails closed
func TestExecutor_OversizedResponse(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		// Send 11 bytes when limit is 10
		_, _ = w.Write([]byte("0123456789X"))
	}))
	defer server.Close()

	policy := httpexecutor.Policy{
		RequestTimeout:   5 * time.Second,
		MaxResponseBytes: 10,
	}

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "tool", OperationName: "op", URL: server.URL},
	}, nil, policy)
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	call := makeBoundCall(t, "tool", "op", []byte(`{"a":1}`))
	_, err = executor.Execute(context.Background(), call)
	if !errors.Is(err, httpexecutor.ErrResponseTooLarge) {
		t.Fatalf("expected ErrResponseTooLarge, got %v", err)
	}
}

// 11. Exact-limit response succeeds
func TestExecutor_ExactLimitResponse(t *testing.T) {
	t.Parallel()

	exactPayload := []byte("0123456789")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(exactPayload)
	}))
	defer server.Close()

	policy := httpexecutor.Policy{
		RequestTimeout:   5 * time.Second,
		MaxResponseBytes: int64(len(exactPayload)),
	}

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "tool", OperationName: "op", URL: server.URL},
	}, nil, policy)
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	call := makeBoundCall(t, "tool", "op", []byte(`{"a":1}`))
	res, err := executor.Execute(context.Background(), call)
	if err != nil {
		t.Fatalf("Execute failed on exact-limit response: %v", err)
	}
	if !bytes.Equal(res.Payload, exactPayload) {
		t.Fatalf("expected payload %s, got %s", string(exactPayload), string(res.Payload))
	}
}

// 12. Non-2xx body redaction (downstream secret canary cannot leak in error)
func TestExecutor_Non2xxBodyRedaction(t *testing.T) {
	t.Parallel()

	canary := "DOWNSTREAM_SECRET_CANARY_X987"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(fmt.Sprintf(`{"secret":"%s"}`, canary)))
	}))
	defer server.Close()

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "tool", OperationName: "op", URL: server.URL},
	}, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	call := makeBoundCall(t, "tool", "op", []byte(`{"test":true}`))
	_, err = executor.Execute(context.Background(), call)
	if !errors.Is(err, httpexecutor.ErrUnexpectedStatus) {
		t.Fatalf("expected ErrUnexpectedStatus, got %v", err)
	}

	errStr := err.Error()
	vStr := fmt.Sprintf("%v", err)
	plusVStr := fmt.Sprintf("%+v", err)

	if strings.Contains(errStr, canary) || strings.Contains(vStr, canary) || strings.Contains(plusVStr, canary) {
		t.Fatalf("CRITICAL SECURITY LEAK: downstream secret canary leaked in error: %s", errStr)
	}
}

// 13. Transport error redaction (transport secret canary cannot leak in error)
func TestExecutor_TransportErrorRedaction(t *testing.T) {
	t.Parallel()

	canary := "TRANSPORT_SECRET_CANARY_K456"
	customTransport := roundTripFunc(func(r *http.Request) (*http.Response, error) {
		return nil, errors.New(canary)
	})

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "tool", OperationName: "op", URL: "http://internal.example.com/test"},
	}, customTransport, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	call := makeBoundCall(t, "tool", "op", []byte(`{"test":true}`))
	_, err = executor.Execute(context.Background(), call)
	if !errors.Is(err, httpexecutor.ErrRequestFailed) {
		t.Fatalf("expected ErrRequestFailed, got %v", err)
	}

	errStr := err.Error()
	vStr := fmt.Sprintf("%v", err)
	plusVStr := fmt.Sprintf("%+v", err)

	if strings.Contains(errStr, canary) || strings.Contains(vStr, canary) || strings.Contains(plusVStr, canary) {
		t.Fatalf("CRITICAL SECURITY LEAK: transport canary leaked in error: %s", errStr)
	}
}

// 14. Zero-value BoundToolCall fails closed with zero network requests
func TestExecutor_ZeroValueBoundCall(t *testing.T) {
	t.Parallel()

	var reqCount int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&reqCount, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "payments", OperationName: "exec", URL: server.URL},
	}, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	_, err = executor.Execute(context.Background(), pep.BoundToolCall{})
	if !errors.Is(err, httpexecutor.ErrInvalidCall) {
		t.Fatalf("expected ErrInvalidCall, got %v", err)
	}
	if atomic.LoadInt32(&reqCount) != 0 {
		t.Fatalf("expected 0 requests, got %d", reqCount)
	}
}

// 15. Context cancellation before Execute
func TestExecutor_ContextCancellation(t *testing.T) {
	t.Parallel()

	var reqCount int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&reqCount, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	executor, err := httpexecutor.NewExecutor([]httpexecutor.Target{
		{ToolName: "tool", OperationName: "op", URL: server.URL},
	}, nil, httpexecutor.Policy{})
	if err != nil {
		t.Fatalf("NewExecutor failed: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // pre-cancel context

	call := makeBoundCall(t, "tool", "op", []byte(`{"x":1}`))
	_, err = executor.Execute(ctx, call)
	if !errors.Is(err, httpexecutor.ErrRequestFailed) {
		t.Fatalf("expected ErrRequestFailed, got %v", err)
	}
	if atomic.LoadInt32(&reqCount) != 0 {
		t.Fatalf("expected 0 requests for pre-canceled context, got %d", reqCount)
	}
}

// 16. Target validation at construction
func TestExecutor_TargetValidation(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name        string
		tool        string
		op          string
		rawURL      string
		expectedErr error
	}{
		{name: "empty tool name", tool: "", op: "op", rawURL: "http://example.com", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "whitespace tool name", tool: "   ", op: "op", rawURL: "http://example.com", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "empty op name", tool: "tool", op: "", rawURL: "http://example.com", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "whitespace op name", tool: "tool", op: "   ", rawURL: "http://example.com", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "relative URL", tool: "tool", op: "op", rawURL: "/v1/api", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "scheme-relative URL", tool: "tool", op: "op", rawURL: "//example.com/v1", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "unsupported scheme ftp", tool: "tool", op: "op", rawURL: "ftp://example.com/file", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "unsupported scheme file", tool: "tool", op: "op", rawURL: "file:///etc/passwd", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "missing host", tool: "tool", op: "op", rawURL: "http://", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "missing hostname port only", tool: "tool", op: "op", rawURL: "http://:8080", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "userinfo present", tool: "tool", op: "op", rawURL: "https://user:pass@example.com/api", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "fragment present", tool: "tool", op: "op", rawURL: "https://example.com/api#fragment", expectedErr: httpexecutor.ErrInvalidTarget},
		{name: "empty fragment present", tool: "tool", op: "op", rawURL: "https://example.com/api#", expectedErr: httpexecutor.ErrInvalidTarget},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := httpexecutor.NewExecutor([]httpexecutor.Target{
				{ToolName: tc.tool, OperationName: tc.op, URL: tc.rawURL},
			}, nil, httpexecutor.Policy{})
			if !errors.Is(err, tc.expectedErr) {
				t.Fatalf("expected error %v, got %v", tc.expectedErr, err)
			}
		})
	}

	// Valid target with path and query parameters
	t.Run("valid target with path and query", func(t *testing.T) {
		_, err := httpexecutor.NewExecutor([]httpexecutor.Target{
			{
				ToolName:      "vault",
				OperationName: "sign",
				URL:           "https://vault.internal:8200/v1/transit/sign/key1?version=2",
			},
		}, nil, httpexecutor.Policy{})
		if err != nil {
			t.Fatalf("expected valid target construction to succeed, got %v", err)
		}
	})
}

// 17. Policy validation
func TestExecutor_PolicyValidation(t *testing.T) {
	t.Parallel()

	validTarget := httpexecutor.Target{
		ToolName:      "tool",
		OperationName: "op",
		URL:           "http://example.com",
	}

	t.Run("negative timeout", func(t *testing.T) {
		_, err := httpexecutor.NewExecutor([]httpexecutor.Target{validTarget}, nil, httpexecutor.Policy{
			RequestTimeout: -1 * time.Second,
		})
		if !errors.Is(err, httpexecutor.ErrInvalidPolicy) {
			t.Fatalf("expected ErrInvalidPolicy, got %v", err)
		}
	})

	t.Run("negative max response bytes", func(t *testing.T) {
		_, err := httpexecutor.NewExecutor([]httpexecutor.Target{validTarget}, nil, httpexecutor.Policy{
			MaxResponseBytes: -100,
		})
		if !errors.Is(err, httpexecutor.ErrInvalidPolicy) {
			t.Fatalf("expected ErrInvalidPolicy, got %v", err)
		}
	})

	t.Run("zero values receive defaults", func(t *testing.T) {
		exec, err := httpexecutor.NewExecutor([]httpexecutor.Target{validTarget}, nil, httpexecutor.Policy{})
		if err != nil {
			t.Fatalf("expected zero policy to succeed with defaults, got %v", err)
		}
		if exec == nil {
			t.Fatal("expected non-nil executor")
		}
	})

	t.Run("max int64 response limit rejected to prevent overflow", func(t *testing.T) {
		_, err := httpexecutor.NewExecutor([]httpexecutor.Target{validTarget}, nil, httpexecutor.Policy{
			MaxResponseBytes: math.MaxInt64,
		})
		if !errors.Is(err, httpexecutor.ErrInvalidPolicy) {
			t.Fatalf("expected ErrInvalidPolicy for math.MaxInt64, got %v", err)
		}
	})

	t.Run("max int64 minus one accepted at constructor boundary", func(t *testing.T) {
		exec, err := httpexecutor.NewExecutor([]httpexecutor.Target{validTarget}, nil, httpexecutor.Policy{
			MaxResponseBytes: math.MaxInt64 - 1,
		})
		if err != nil {
			t.Fatalf("expected math.MaxInt64 - 1 to be accepted, got %v", err)
		}
		if exec == nil {
			t.Fatal("expected non-nil executor")
		}
	})
}

// 18. Response limit overflow edge: math.MaxInt64 rejected
func TestExecutor_ResponseLimitOverflowProtection(t *testing.T) {
	t.Parallel()

	validTarget := httpexecutor.Target{
		ToolName:      "tool",
		OperationName: "op",
		URL:           "http://example.com",
	}

	_, err := httpexecutor.NewExecutor([]httpexecutor.Target{validTarget}, nil, httpexecutor.Policy{
		MaxResponseBytes: math.MaxInt64,
	})
	if !errors.Is(err, httpexecutor.ErrInvalidPolicy) {
		t.Fatalf("expected ErrInvalidPolicy when MaxResponseBytes == math.MaxInt64, got %v", err)
	}
}

// 19. Uninitialized or nil executor call fails closed
func TestExecutor_Uninitialized(t *testing.T) {
	t.Parallel()

	var nilExec *httpexecutor.Executor
	_, err := nilExec.Execute(context.Background(), pep.BoundToolCall{})
	if !errors.Is(err, httpexecutor.ErrInvalidExecutor) {
		t.Fatalf("expected ErrInvalidExecutor on nil executor, got %v", err)
	}

	emptyExec := &httpexecutor.Executor{}
	_, err = emptyExec.Execute(context.Background(), pep.BoundToolCall{})
	if !errors.Is(err, httpexecutor.ErrInvalidExecutor) {
		t.Fatalf("expected ErrInvalidExecutor on uninitialized executor, got %v", err)
	}
}

// helper RoundTripper for mocking network behavior
type roundTripFunc func(req *http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}
