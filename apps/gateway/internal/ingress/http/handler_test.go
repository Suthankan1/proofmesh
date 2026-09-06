package httpingress_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/canonicalize"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
	httpingress "github.com/Suthankan1/proofmesh/apps/gateway/internal/ingress/http"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
)

// --- Test Fakes ---

type fakeVerifier struct {
	mu          sync.Mutex
	calls       int
	lastToken   string
	lastCtx     context.Context
	returnGrant executiongrant.VerifiedExecutionGrant
	returnErr   error
}

func (f *fakeVerifier) Verify(ctx context.Context, compactToken string) (executiongrant.VerifiedExecutionGrant, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	f.lastToken = compactToken
	f.lastCtx = ctx
	if f.returnErr != nil {
		return executiongrant.VerifiedExecutionGrant{}, f.returnErr
	}
	return f.returnGrant, nil
}

type fakeAuthority struct {
	mu             sync.Mutex
	calls          int
	lastCall       pep.BoundToolCall
	scriptedResult pep.ClaimResult
	scriptedErr    error
}

func (f *fakeAuthority) Claim(ctx context.Context, call pep.BoundToolCall) (pep.ClaimResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	f.lastCall = call
	if f.scriptedErr != nil {
		return pep.ClaimResultUnknown, f.scriptedErr
	}
	if f.scriptedResult != pep.ClaimResultUnknown {
		return f.scriptedResult, nil
	}
	return pep.ClaimAcquired, nil
}

type recordingExecutor struct {
	mu        sync.Mutex
	calls     int
	lastCall  pep.BoundToolCall
	returnRes pep.ToolResult
	returnErr error
}

func (r *recordingExecutor) Execute(ctx context.Context, call pep.BoundToolCall) (pep.ToolResult, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls++
	r.lastCall = call
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

// --- Helper Functions ---

type testHarness struct {
	verifier  *fakeVerifier
	authority *fakeAuthority
	executor  *recordingExecutor
	clock     *controllableClock
	enforcer  *pep.Enforcer
	handler   *httpingress.Handler
}

func newTestHarness(t *testing.T) *testHarness {
	t.Helper()
	v := &fakeVerifier{}
	a := &fakeAuthority{scriptedResult: pep.ClaimAcquired}
	e := &recordingExecutor{returnRes: pep.ToolResult{Payload: []byte(`{"status":"success"}`)}}
	c := &controllableClock{now: time.Date(2026, 9, 6, 12, 0, 0, 0, time.UTC)}

	enf, err := pep.NewEnforcer(v, a, e, c)
	if err != nil {
		t.Fatalf("failed to create enforcer: %v", err)
	}

	h, err := httpingress.NewHandler(enf)
	if err != nil {
		t.Fatalf("failed to create handler: %v", err)
	}

	return &testHarness{
		verifier:  v,
		authority: a,
		executor:  e,
		clock:     c,
		enforcer:  enf,
		handler:   h,
	}
}

func validEnvelopeMap(rawPayload string) map[string]any {
	return map[string]any{
		"organization_id":        "11111111-1111-1111-1111-111111111111",
		"agent_id":               "22222222-2222-2222-2222-222222222222",
		"governed_action_id":     "33333333-3333-3333-3333-333333333333",
		"governance_decision_id": "44444444-4444-4444-4444-444444444444",
		"tool_name":              "payments",
		"operation_name":         "execute",
		"payload":                json.RawMessage(rawPayload),
	}
}

func matchingGrantForEnvelope(m map[string]any, exp time.Time) (executiongrant.VerifiedExecutionGrant, error) {
	rawPayload, _ := m["payload"].(json.RawMessage)
	_, hash, err := canonicalize.CanonicalizeAndHash(rawPayload)
	if err != nil {
		return executiongrant.VerifiedExecutionGrant{}, err
	}

	return executiongrant.VerifiedExecutionGrant{
		GrantID:              uuid.MustParse("99999999-9999-9999-9999-999999999999"),
		OrganizationID:       uuid.MustParse(m["organization_id"].(string)),
		AgentID:              uuid.MustParse(m["agent_id"].(string)),
		GovernedActionID:     uuid.MustParse(m["governed_action_id"].(string)),
		GovernanceDecisionID: uuid.MustParse(m["governance_decision_id"].(string)),
		ToolName:             m["tool_name"].(string),
		OperationName:        m["operation_name"].(string),
		PayloadHash:          hash,
		Issuer:               "https://controlplane.proofmesh.internal",
		Audience:             "proofmesh-gateway",
		IssuedAt:             exp.Add(-30 * time.Second),
		ExpiresAt:            exp,
	}, nil
}

// --- Section 28: Constructor Tests ---

func TestNewHandler_Validation(t *testing.T) {
	t.Parallel()

	h, err := httpingress.NewHandler(nil)
	if !errors.Is(err, httpingress.ErrNilEnforcer) {
		t.Fatalf("expected ErrNilEnforcer, got: %v", err)
	}
	if h != nil {
		t.Fatalf("expected nil handler, got %v", h)
	}

	harness := newTestHarness(t)
	if harness.handler == nil {
		t.Fatal("expected non-nil handler")
	}

	var _ http.Handler = harness.handler
}

// --- Section 29: Route and Method Tests ---

func TestHandler_RouteAndMethod(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	tests := []struct {
		name           string
		method         string
		path           string
		expectedStatus int
		expectedHeader string
		expectedAllow  string
	}{
		{
			name:           "GET /v1/executions returns 405 with Allow POST",
			method:         http.MethodGet,
			path:           "/v1/executions",
			expectedStatus: http.StatusMethodNotAllowed,
			expectedAllow:  http.MethodPost,
		},
		{
			name:           "PUT /v1/executions returns 405",
			method:         http.MethodPut,
			path:           "/v1/executions",
			expectedStatus: http.StatusMethodNotAllowed,
			expectedAllow:  http.MethodPost,
		},
		{
			name:           "DELETE /v1/executions returns 405",
			method:         http.MethodDelete,
			path:           "/v1/executions",
			expectedStatus: http.StatusMethodNotAllowed,
			expectedAllow:  http.MethodPost,
		},
		{
			name:           "PATCH /v1/executions returns 405",
			method:         http.MethodPatch,
			path:           "/v1/executions",
			expectedStatus: http.StatusMethodNotAllowed,
			expectedAllow:  http.MethodPost,
		},
		{
			name:           "POST /v1/other returns 404",
			method:         http.MethodPost,
			path:           "/v1/other",
			expectedStatus: http.StatusNotFound,
		},
		{
			name:           "POST / returns 404",
			method:         http.MethodPost,
			path:           "/",
			expectedStatus: http.StatusNotFound,
		},
		{
			name:           "POST /v1/executions/extra returns 404",
			method:         http.MethodPost,
			path:           "/v1/executions/extra",
			expectedStatus: http.StatusNotFound,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			req := httptest.NewRequest(tc.method, tc.path, nil)
			rec := httptest.NewRecorder()

			harness.handler.ServeHTTP(rec, req)

			if rec.Code != tc.expectedStatus {
				t.Fatalf("expected status %d, got %d", tc.expectedStatus, rec.Code)
			}
			if got := rec.Header().Get("Cache-Control"); got != "no-store" {
				t.Errorf("expected Cache-Control: no-store, got %q", got)
			}
			if tc.expectedAllow != "" {
				if got := rec.Header().Get("Allow"); got != tc.expectedAllow {
					t.Errorf("expected Allow %q, got %q", tc.expectedAllow, got)
				}
			}
		})
	}
}

// --- Section 30: Authorization Header Tests ---

func TestHandler_Authorization(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name        string
		headers     []string
		description string
	}{
		{
			name:        "missing Authorization header",
			headers:     nil,
			description: "no auth header provided",
		},
		{
			name:        "duplicate Authorization headers",
			headers:     []string{"Bearer token1", "Bearer token2"},
			description: "multiple values rejected",
		},
		{
			name:        "Basic scheme rejected",
			headers:     []string{"Basic dXNlcjpwYXNz"},
			description: "non-Bearer scheme",
		},
		{
			name:        "scheme only",
			headers:     []string{"Bearer"},
			description: "missing token part",
		},
		{
			name:        "blank bearer",
			headers:     []string{"Bearer "},
			description: "whitespace only token",
		},
		{
			name:        "multiple spaces only",
			headers:     []string{"Bearer    "},
			description: "spaces only token",
		},
		{
			name:        "Bearer with extra tokens",
			headers:     []string{"Bearer token1 token2"},
			description: "extra credential parts",
		},
		{
			name:        "token containing tab",
			headers:     []string{"Bearer token\twithtab"},
			description: "whitespace in token",
		},
		{
			name:        "comma-separated credentials",
			headers:     []string{"Bearer token1, Bearer token2"},
			description: "comma separated tokens",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			harness := newTestHarness(t)

			req := httptest.NewRequest(http.MethodPost, "/v1/executions", strings.NewReader(`{"dummy":"json"}`))
			for _, h := range tc.headers {
				req.Header.Add("Authorization", h)
			}
			rec := httptest.NewRecorder()

			harness.handler.ServeHTTP(rec, req)

			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("expected 401, got %d. body: %s", rec.Code, rec.Body.String())
			}
			if got := rec.Header().Get("WWW-Authenticate"); got != "Bearer" {
				t.Errorf("expected WWW-Authenticate: Bearer, got %q", got)
			}
			if got := rec.Header().Get("Cache-Control"); got != "no-store" {
				t.Errorf("expected Cache-Control: no-store, got %q", got)
			}

			// Enforcer / Verifier must never be reached
			if harness.verifier.calls != 0 {
				t.Errorf("verifier called %d times, expected 0", harness.verifier.calls)
			}
			if harness.authority.calls != 0 {
				t.Errorf("authority called %d times, expected 0", harness.authority.calls)
			}
			if harness.executor.calls != 0 {
				t.Errorf("executor called %d times, expected 0", harness.executor.calls)
			}
		})
	}
}

// --- Section 31: Strict Envelope Parsing Tests ---

func TestHandler_StrictEnvelope(t *testing.T) {
	t.Parallel()

	validBody := `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`

	tests := []struct {
		name string
		body string
	}{
		{
			name: "empty body",
			body: "",
		},
		{
			name: "whitespace body",
			body: "   \n\t  ",
		},
		{
			name: "malformed JSON syntax",
			body: "{not-valid-json}",
		},
		{
			name: "array top level",
			body: `[{"organization_id":"11111111-1111-1111-1111-111111111111"}]`,
		},
		{
			name: "null top level",
			body: "null",
		},
		{
			name: "string top level",
			body: `"just-a-string"`,
		},
		{
			name: "number top level",
			body: "12345",
		},
		{
			name: "boolean top level",
			body: "true",
		},
		{
			name: "unknown field rejected",
			body: `{"unknown_field":"forbidden",` + validBody[1:],
		},
		{
			name: "duplicate organization_id",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111",` + validBody[1:],
		},
		{
			name: "duplicate tool_name",
			body: `{"tool_name":"payments",` + validBody[1:],
		},
		{
			name: "duplicate payload",
			body: `{"payload":{"amount":5000},` + validBody[1:],
		},
		{
			name: "trailing garbage after closing brace",
			body: validBody + ` garbage`,
		},
		{
			name: "second JSON value after closing brace",
			body: validBody + ` {"second":"value"}`,
		},
		{
			name: "missing organization_id",
			body: `{"agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "missing agent_id",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "missing governed_action_id",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "missing governance_decision_id",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "missing tool_name",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "missing operation_name",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","payload":{"amount":5000}}`,
		},
		{
			name: "missing payload",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute"}`,
		},
		{
			name: "malformed organization_id UUID",
			body: `{"organization_id":"not-a-valid-uuid","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "nil organization_id UUID rejected",
			body: `{"organization_id":"00000000-0000-0000-0000-000000000000","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "nil agent_id UUID rejected",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"00000000-0000-0000-0000-000000000000","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "nil governed_action_id UUID rejected",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"00000000-0000-0000-0000-000000000000","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "nil governance_decision_id UUID rejected",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"00000000-0000-0000-0000-000000000000","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "blank tool_name rejected",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"   ","operation_name":"execute","payload":{"amount":5000}}`,
		},
		{
			name: "blank operation_name rejected",
			body: `{"organization_id":"11111111-1111-1111-1111-111111111111","agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"   ","payload":{"amount":5000}}`,
		},
		{
			name: "non-string organization_id rejected",
			body: `{"organization_id":12345,"agent_id":"22222222-2222-2222-2222-222222222222","governed_action_id":"33333333-3333-3333-3333-333333333333","governance_decision_id":"44444444-4444-4444-4444-444444444444","tool_name":"payments","operation_name":"execute","payload":{"amount":5000}}`,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			harness := newTestHarness(t)

			req := httptest.NewRequest(http.MethodPost, "/v1/executions", strings.NewReader(tc.body))
			req.Header.Set("Authorization", "Bearer valid.compact.token")
			rec := httptest.NewRecorder()

			harness.handler.ServeHTTP(rec, req)

			if rec.Code != http.StatusBadRequest {
				t.Fatalf("expected status 400, got %d. body: %s", rec.Code, rec.Body.String())
			}
			if got := rec.Header().Get("Cache-Control"); got != "no-store" {
				t.Errorf("expected Cache-Control: no-store, got %q", got)
			}
			var resp map[string]string
			if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
				t.Fatalf("response body is not valid JSON: %v", err)
			}
			if resp["error"] != "invalid_request" {
				t.Errorf("expected error invalid_request, got %q", resp["error"])
			}

			// Must not reach Enforcer
			if harness.verifier.calls != 0 {
				t.Errorf("verifier was called %d times, expected 0", harness.verifier.calls)
			}
		})
	}
}

// --- Section 32: Oversize Request Body Test ---

func TestHandler_OversizeBody(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	// Create request body larger than DefaultMaxRequestBodyBytes (1 MiB)
	oversizeBytes := int(httpingress.DefaultMaxRequestBodyBytes) + 64
	largeBody := bytes.Repeat([]byte("a"), oversizeBytes)

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(largeBody))
	req.Header.Set("Authorization", "Bearer valid.compact.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not valid JSON: %v", err)
	}
	if resp["error"] != "payload_too_large" {
		t.Errorf("expected payload_too_large error code, got %q", resp["error"])
	}

	if harness.verifier.calls != 0 {
		t.Errorf("verifier was called %d times, expected 0", harness.verifier.calls)
	}
}

// --- Section 33: Positive Enforcer Integration Test ---

func TestHandler_Success(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	exp := harness.clock.Now().Add(30 * time.Second)
	rawPayload := "{\n  \"paymentId\": \"pay_123\",\n  \"amount\": 5000\n}"
	envMap := validEnvelopeMap(rawPayload)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create matching grant: %v", err)
	}

	harness.verifier.returnGrant = grant
	harness.authority.scriptedResult = pep.ClaimAcquired
	expectedResultPayload := []byte("binary-tool-result-\x00\x01\x02")
	harness.executor.returnRes = pep.ToolResult{Payload: expectedResultPayload}

	envJSON, err := json.Marshal(envMap)
	if err != nil {
		t.Fatalf("failed to marshal envelope: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer test.compact.jwt.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Content-Type"); got != "application/octet-stream" {
		t.Errorf("expected Content-Type: application/octet-stream, got %q", got)
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	if !bytes.Equal(rec.Body.Bytes(), expectedResultPayload) {
		t.Fatalf("expected payload %q, got %q", expectedResultPayload, rec.Body.Bytes())
	}
}

// --- Section 34: Exact Token Handoff Test ---

func TestHandler_ExactTokenHandoff(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	exp := harness.clock.Now().Add(30 * time.Second)
	envMap := validEnvelopeMap(`{"foo":"bar"}`)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create grant: %v", err)
	}
	harness.verifier.returnGrant = grant

	envJSON, _ := json.Marshal(envMap)
	rawToken := "eyJhbGciOiJFUzI1NiJ9.exact-token-bytes-preserved.sig"

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer "+rawToken)
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if harness.verifier.lastToken != rawToken {
		t.Fatalf("expected verifier to receive exact token %q, got %q", rawToken, harness.verifier.lastToken)
	}
}

// --- Section 35: Exact Attempt Handoff and Payload Preservation Test ---

func TestHandler_PayloadPreservationAndAttemptHandoff(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	exp := harness.clock.Now().Add(30 * time.Second)

	// Raw payload contains intentional spaces and newlines
	rawPayload := "{\n  \"paymentId\": \"pay_123\",\n  \"amount\": 5000\n}"
	envMap := validEnvelopeMap(rawPayload)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create grant: %v", err)
	}
	harness.verifier.returnGrant = grant

	envJSON, _ := json.Marshal(envMap)

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer my.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d. body: %s", rec.Code, rec.Body.String())
	}

	// Verify the attempt fields reached executor
	call := harness.executor.lastCall
	if call.OrganizationID() != grant.OrganizationID {
		t.Errorf("org ID mismatch")
	}
	if call.AgentID() != grant.AgentID {
		t.Errorf("agent ID mismatch")
	}
	if call.GovernedActionID() != grant.GovernedActionID {
		t.Errorf("action ID mismatch")
	}
	if call.GovernanceDecisionID() != grant.GovernanceDecisionID {
		t.Errorf("decision ID mismatch")
	}
	if call.ToolName() != "payments" {
		t.Errorf("tool name mismatch")
	}
	if call.OperationName() != "execute" {
		t.Errorf("operation name mismatch")
	}

	// Canonical payload should match RFC 8785 canonicalization of rawPayload
	canonicalExpected, _, _ := canonicalize.CanonicalizeAndHash([]byte(rawPayload))
	if !bytes.Equal(call.Payload(), canonicalExpected) {
		t.Fatalf("expected canonical payload %q, got %q", canonicalExpected, call.Payload())
	}
}

// --- Section 36: Replay Mapping Test ---

func TestHandler_ReplayMapping(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	exp := harness.clock.Now().Add(30 * time.Second)
	envMap := validEnvelopeMap(`{"foo":"bar"}`)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create grant: %v", err)
	}
	harness.verifier.returnGrant = grant
	harness.authority.scriptedResult = pep.ClaimReplay

	envJSON, _ := json.Marshal(envMap)

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer replay.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusConflict {
		t.Fatalf("expected 409 Conflict, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not valid JSON: %v", err)
	}
	if resp["error"] != "conflict" {
		t.Errorf("expected error code conflict, got %q", resp["error"])
	}

	if harness.executor.calls != 0 {
		t.Errorf("executor was called %d times, expected 0", harness.executor.calls)
	}
}

// --- Section 37: Authority Failure Mapping Test ---

func TestHandler_AuthorityFailureMapping(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	exp := harness.clock.Now().Add(30 * time.Second)
	envMap := validEnvelopeMap(`{"foo":"bar"}`)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create grant: %v", err)
	}
	harness.verifier.returnGrant = grant
	canaryErr := errors.New("db connection timeout secret-canary-authority-12345")
	harness.authority.scriptedErr = canaryErr

	envJSON, _ := json.Marshal(envMap)

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer valid.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503 Service Unavailable, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	if strings.Contains(rec.Body.String(), "secret-canary-authority-12345") {
		t.Fatal("canary leaked in response body!")
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not valid JSON: %v", err)
	}
	if resp["error"] != "service_unavailable" {
		t.Errorf("expected service_unavailable, got %q", resp["error"])
	}

	if harness.executor.calls != 0 {
		t.Errorf("executor was called %d times, expected 0", harness.executor.calls)
	}
}

// --- Section 38: Tool Execution Failure Mapping Test ---

func TestHandler_ToolExecutionFailureMapping(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	exp := harness.clock.Now().Add(30 * time.Second)
	envMap := validEnvelopeMap(`{"foo":"bar"}`)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create grant: %v", err)
	}
	harness.verifier.returnGrant = grant
	harness.authority.scriptedResult = pep.ClaimAcquired

	canaryErr := errors.New("downstream 500 error secret-canary-tool-98765")
	harness.executor.returnErr = canaryErr

	envJSON, _ := json.Marshal(envMap)

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer valid.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadGateway {
		t.Fatalf("expected 502 Bad Gateway, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	if strings.Contains(rec.Body.String(), "secret-canary-tool-98765") {
		t.Fatal("downstream canary leaked in response body!")
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not valid JSON: %v", err)
	}
	if resp["error"] != "bad_gateway" {
		t.Errorf("expected bad_gateway, got %q", resp["error"])
	}
}

// --- Section 39: Binding Mismatch Test ---

func TestHandler_BindingMismatch(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	exp := harness.clock.Now().Add(30 * time.Second)
	envMap := validEnvelopeMap(`{"foo":"bar"}`)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create grant: %v", err)
	}

	// Change grant's OrganizationID to cause binding mismatch
	grant.OrganizationID = uuid.MustParse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
	harness.verifier.returnGrant = grant

	envJSON, _ := json.Marshal(envMap)

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer mismatch.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not valid JSON: %v", err)
	}
	if resp["error"] != "forbidden" {
		t.Errorf("expected forbidden, got %q", resp["error"])
	}

	if harness.authority.calls != 0 {
		t.Errorf("authority was called %d times, expected 0", harness.authority.calls)
	}
	if harness.executor.calls != 0 {
		t.Errorf("executor was called %d times, expected 0", harness.executor.calls)
	}
}

// --- Section 40: Invalid Grant & Expired Grant Tests ---

func TestHandler_InvalidGrant(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	harness.verifier.returnErr = errors.New("signature verification failed: secret-key-canary")

	envMap := validEnvelopeMap(`{"foo":"bar"}`)
	envJSON, _ := json.Marshal(envMap)

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer invalid.signature.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("WWW-Authenticate"); got != "Bearer" {
		t.Errorf("expected WWW-Authenticate: Bearer, got %q", got)
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	if strings.Contains(rec.Body.String(), "secret-key-canary") {
		t.Fatal("verifier canary leaked in response body!")
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not valid JSON: %v", err)
	}
	if resp["error"] != "invalid_token" {
		t.Errorf("expected invalid_token, got %q", resp["error"])
	}

	if harness.authority.calls != 0 {
		t.Errorf("authority was called %d times, expected 0", harness.authority.calls)
	}
	if harness.executor.calls != 0 {
		t.Errorf("executor was called %d times, expected 0", harness.executor.calls)
	}
}

func TestHandler_ExpiredGrant(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	// Grant expired in the past relative to clock
	exp := harness.clock.Now().Add(-10 * time.Second)
	envMap := validEnvelopeMap(`{"foo":"bar"}`)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create grant: %v", err)
	}
	harness.verifier.returnGrant = grant

	envJSON, _ := json.Marshal(envMap)

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
	req.Header.Set("Authorization", "Bearer expired.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("WWW-Authenticate"); got != "Bearer" {
		t.Errorf("expected WWW-Authenticate: Bearer, got %q", got)
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not valid JSON: %v", err)
	}
	if resp["error"] != "invalid_token" {
		t.Errorf("expected invalid_token, got %q", resp["error"])
	}

	if harness.authority.calls != 0 {
		t.Errorf("authority was called %d times, expected 0", harness.authority.calls)
	}
	if harness.executor.calls != 0 {
		t.Errorf("executor was called %d times, expected 0", harness.executor.calls)
	}
}

// --- Section 41: Response Redaction and Leakage Tests ---

func TestHandler_Redaction(t *testing.T) {
	t.Parallel()

	tokenCanary := "canary-secret-token-abcdef12345"
	authorityCanary := "canary-secret-db-connection-failed-xyz"
	executorCanary := "canary-secret-downstream-internal-leak-789"
	requestCanary := "canary-secret-request-syntax-leak-999"

	tests := []struct {
		name         string
		setupReq     func(harness *testHarness) *http.Request
		canaryString string
	}{
		{
			name: "token canary not echoed in 400 error",
			setupReq: func(harness *testHarness) *http.Request {
				req := httptest.NewRequest(http.MethodPost, "/v1/executions", strings.NewReader("bad-json"))
				req.Header.Set("Authorization", "Bearer "+tokenCanary)
				return req
			},
			canaryString: tokenCanary,
		},
		{
			name: "token canary not echoed in 401 error",
			setupReq: func(harness *testHarness) *http.Request {
				req := httptest.NewRequest(http.MethodPost, "/v1/executions", strings.NewReader(`{"dummy":1}`))
				req.Header.Set("Authorization", "Bearer "+tokenCanary+" extra")
				return req
			},
			canaryString: tokenCanary,
		},
		{
			name: "authority canary not echoed in 503",
			setupReq: func(harness *testHarness) *http.Request {
				exp := harness.clock.Now().Add(30 * time.Second)
				envMap := validEnvelopeMap(`{"foo":"bar"}`)
				grant, _ := matchingGrantForEnvelope(envMap, exp)
				harness.verifier.returnGrant = grant
				harness.authority.scriptedErr = errors.New(authorityCanary)

				envJSON, _ := json.Marshal(envMap)
				req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
				req.Header.Set("Authorization", "Bearer valid.token")
				return req
			},
			canaryString: authorityCanary,
		},
		{
			name: "executor canary not echoed in 502",
			setupReq: func(harness *testHarness) *http.Request {
				exp := harness.clock.Now().Add(30 * time.Second)
				envMap := validEnvelopeMap(`{"foo":"bar"}`)
				grant, _ := matchingGrantForEnvelope(envMap, exp)
				harness.verifier.returnGrant = grant
				harness.authority.scriptedResult = pep.ClaimAcquired
				harness.executor.returnErr = errors.New(executorCanary)

				envJSON, _ := json.Marshal(envMap)
				req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON))
				req.Header.Set("Authorization", "Bearer valid.token")
				return req
			},
			canaryString: executorCanary,
		},
		{
			name: "request payload syntax canary not echoed in 400",
			setupReq: func(harness *testHarness) *http.Request {
				req := httptest.NewRequest(http.MethodPost, "/v1/executions", strings.NewReader("{"+requestCanary+":bad}"))
				req.Header.Set("Authorization", "Bearer valid.token")
				return req
			},
			canaryString: requestCanary,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			harness := newTestHarness(t)
			req := tc.setupReq(harness)
			rec := httptest.NewRecorder()

			harness.handler.ServeHTTP(rec, req)

			bodyStr := rec.Body.String()
			if strings.Contains(bodyStr, tc.canaryString) {
				t.Fatalf("body contains canary %q: %s", tc.canaryString, bodyStr)
			}
			for k, v := range rec.Header() {
				for _, val := range v {
					if strings.Contains(val, tc.canaryString) {
						t.Fatalf("header %q contains canary %q: %s", k, tc.canaryString, val)
					}
				}
			}
		})
	}
}

func TestHandler_ContextCancellation(t *testing.T) {
	t.Parallel()

	harness := newTestHarness(t)

	exp := harness.clock.Now().Add(30 * time.Second)
	envMap := validEnvelopeMap(`{"foo":"bar"}`)
	grant, err := matchingGrantForEnvelope(envMap, exp)
	if err != nil {
		t.Fatalf("failed to create grant: %v", err)
	}
	harness.verifier.returnGrant = grant

	envJSON, _ := json.Marshal(envMap)

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	req := httptest.NewRequest(http.MethodPost, "/v1/executions", bytes.NewReader(envJSON)).WithContext(ctx)
	req.Header.Set("Authorization", "Bearer valid.token")
	rec := httptest.NewRecorder()

	harness.handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusGatewayTimeout {
		t.Fatalf("expected 504 Gateway Timeout, got %d. body: %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("expected Cache-Control: no-store, got %q", got)
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("response body is not valid JSON: %v", err)
	}
	if resp["error"] != "execution_canceled" {
		t.Errorf("expected execution_canceled, got %q", resp["error"])
	}

	if harness.executor.calls != 0 {
		t.Errorf("executor was called %d times, expected 0", harness.executor.calls)
	}
}
