package httpexecutor

import (
	"bytes"
	"context"
	"errors"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
)

var _ pep.ToolExecutor = (*Executor)(nil)

// Sentinel errors returned by the HTTP tool executor adapter.
var (
	ErrInvalidTarget    = errors.New("toolexecutor/http: invalid target configuration")
	ErrDuplicateTarget  = errors.New("toolexecutor/http: duplicate target configuration")
	ErrInvalidPolicy    = errors.New("toolexecutor/http: invalid policy")
	ErrInvalidCall      = errors.New("toolexecutor/http: invalid bound tool call")
	ErrUnknownTarget    = errors.New("toolexecutor/http: unknown target")
	ErrRequestFailed    = errors.New("toolexecutor/http: request failed")
	ErrUnexpectedStatus = errors.New("toolexecutor/http: unexpected HTTP status")
	ErrResponseTooLarge = errors.New("toolexecutor/http: response body exceeds configured limit")
	ErrInvalidExecutor  = errors.New("toolexecutor/http: invalid executor initialization")
)

const (
	// DefaultRequestTimeout is the default maximum duration for an outbound tool invocation.
	DefaultRequestTimeout = 30 * time.Second

	// DefaultMaxResponseBytes is the default maximum response body size (2 MiB).
	DefaultMaxResponseBytes = 2 * 1024 * 1024
)

// Target defines a trusted downstream tool endpoint mapping.
// Destination URLs are strictly looked up by the exact pair (ToolName, OperationName)
// and are never derived from untrusted attempt payloads or caller headers.
type Target struct {
	ToolName      string
	OperationName string
	URL           string
}

// Policy specifies bounded execution limits for outbound HTTP invocations.
type Policy struct {
	RequestTimeout   time.Duration
	MaxResponseBytes int64
}

type targetKey struct {
	toolName      string
	operationName string
}

// Executor is a production-capable outbound HTTP adapter implementing pep.ToolExecutor.
// It executes strictly one outbound POST request per Execute call, disables redirects,
// performs zero retries, sends exact RFC 8785 canonical bytes, and sanitizes downstream errors.
type Executor struct {
	targets map[targetKey]string
	client  *http.Client
	policy  Policy
}

// NewExecutor constructs an Executor with validated, immutable target mappings.
// If transport is nil, http.DefaultTransport is used.
// Zero-value policy fields default to safe operational bounds.
func NewExecutor(
	targets []Target,
	transport http.RoundTripper,
	policy Policy,
) (*Executor, error) {
	if policy.RequestTimeout < 0 || policy.MaxResponseBytes < 0 || policy.MaxResponseBytes >= math.MaxInt64 {
		return nil, ErrInvalidPolicy
	}
	if policy.RequestTimeout == 0 {
		policy.RequestTimeout = DefaultRequestTimeout
	}
	if policy.MaxResponseBytes == 0 {
		policy.MaxResponseBytes = DefaultMaxResponseBytes
	}

	targetsMap := make(map[targetKey]string, len(targets))
	for _, t := range targets {
		if strings.TrimSpace(t.ToolName) == "" || strings.TrimSpace(t.OperationName) == "" {
			return nil, ErrInvalidTarget
		}
		validatedURL, err := validateTargetURL(t.URL)
		if err != nil {
			return nil, err
		}
		key := targetKey{
			toolName:      t.ToolName,
			operationName: t.OperationName,
		}
		if _, exists := targetsMap[key]; exists {
			return nil, ErrDuplicateTarget
		}
		targetsMap[key] = validatedURL
	}

	tr := transport
	if tr == nil {
		tr = http.DefaultTransport
	}

	client := &http.Client{
		Transport: tr,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	return &Executor{
		targets: targetsMap,
		client:  client,
		policy:  policy,
	}, nil
}

// Execute performs a single protected outbound HTTP POST request for a verified, bound tool call.
// It enforces:
//  1. Valid executor state.
//  2. Strict BoundToolCall completeness validation.
//  3. Exact (tool_name, operation_name) target resolution.
//  4. Context cancellation verification.
//  5. Outbound request context bounded by configured timeout.
//  6. Body set to exact canonical payload bytes without remarshaling.
//  7. Isolated headers (Content-Type and Accept only; no tokens/credentials forwarded).
//  8. Single HTTP POST dispatch with disabled redirects and no retries.
//  9. Non-2xx fail-closed semantics without leaking downstream response bodies.
//  10. Bounded response reading preventing memory exhaustion.
//  11. Complete error sanitization across transport and downstream boundaries.
func (e *Executor) Execute(ctx context.Context, call pep.BoundToolCall) (pep.ToolResult, error) {
	if e == nil || e.targets == nil || e.client == nil {
		return pep.ToolResult{}, ErrInvalidExecutor
	}

	if err := validateBoundCall(call); err != nil {
		return pep.ToolResult{}, err
	}

	key := targetKey{
		toolName:      call.ToolName(),
		operationName: call.OperationName(),
	}
	targetURL, ok := e.targets[key]
	if !ok {
		return pep.ToolResult{}, ErrUnknownTarget
	}

	if ctx.Err() != nil {
		return pep.ToolResult{}, ErrRequestFailed
	}

	reqCtx, cancel := context.WithTimeout(ctx, e.policy.RequestTimeout)
	defer cancel()

	payloadBytes := call.Payload()
	req, err := http.NewRequestWithContext(reqCtx, http.MethodPost, targetURL, bytes.NewReader(payloadBytes))
	if err != nil {
		return pep.ToolResult{}, ErrRequestFailed
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := e.client.Do(req)
	if err != nil {
		return pep.ToolResult{}, ErrRequestFailed
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return pep.ToolResult{}, ErrUnexpectedStatus
	}

	limitedReader := io.LimitReader(resp.Body, e.policy.MaxResponseBytes+1)
	body, err := io.ReadAll(limitedReader)
	if err != nil {
		return pep.ToolResult{}, ErrRequestFailed
	}
	if int64(len(body)) > e.policy.MaxResponseBytes {
		return pep.ToolResult{}, ErrResponseTooLarge
	}

	return pep.ToolResult{
		Payload: body,
	}, nil
}

func validateTargetURL(rawURL string) (string, error) {
	if strings.Contains(rawURL, "#") {
		return "", ErrInvalidTarget
	}
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return "", ErrInvalidTarget
	}
	if !parsed.IsAbs() {
		return "", ErrInvalidTarget
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", ErrInvalidTarget
	}
	if parsed.Host == "" || strings.TrimSpace(parsed.Hostname()) == "" {
		return "", ErrInvalidTarget
	}
	if parsed.User != nil {
		return "", ErrInvalidTarget
	}
	if parsed.Fragment != "" {
		return "", ErrInvalidTarget
	}
	return parsed.String(), nil
}

func validateBoundCall(call pep.BoundToolCall) error {
	if call.GrantID() == uuid.Nil ||
		call.OrganizationID() == uuid.Nil ||
		call.AgentID() == uuid.Nil ||
		call.GovernedActionID() == uuid.Nil ||
		call.GovernanceDecisionID() == uuid.Nil ||
		strings.TrimSpace(call.ToolName()) == "" ||
		strings.TrimSpace(call.OperationName()) == "" ||
		strings.TrimSpace(call.PayloadHash()) == "" ||
		call.ExpiresAt().IsZero() ||
		len(call.Payload()) == 0 {
		return ErrInvalidCall
	}
	return nil
}
