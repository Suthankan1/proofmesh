package pep

import (
	"bytes"
	"context"
	"strings"

	"github.com/google/uuid"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executionattempt"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
)

// GrantVerifier abstracts cryptographic verification of an untrusted compact execution-grant token.
// The existing *executiongrant.Verifier satisfies this interface.
type GrantVerifier interface {
	Verify(ctx context.Context, compactToken string) (executiongrant.VerifiedExecutionGrant, error)
}

// Compile-time check that *executiongrant.Verifier satisfies GrantVerifier.
var _ GrantVerifier = (*executiongrant.Verifier)(nil)

// Enforcer is the core Policy Enforcement Point (PEP) orchestrator for protected tool execution.
// It strictly orchestrates token verification, exact attempt binding, bound-object sanity validation,
// pre-claim expiry and context checks, atomic single-use replay admission claim,
// and post-claim context and expiry checks, before invoking the injected ToolExecutor.
//
// Real protected-tool side effects must NOT be enabled before durable replay authority exists.
type Enforcer struct {
	verifier  GrantVerifier
	authority ExecutionAuthority
	executor  ToolExecutor
	clock     executiongrant.Clock
}

// NewEnforcer constructs a new Enforcer with explicit non-nil dependency injection.
func NewEnforcer(
	verifier GrantVerifier,
	authority ExecutionAuthority,
	executor ToolExecutor,
	clock executiongrant.Clock,
) (*Enforcer, error) {
	if verifier == nil {
		return nil, ErrNilVerifier
	}
	if authority == nil {
		return nil, ErrNilAuthority
	}
	if executor == nil {
		return nil, ErrNilExecutor
	}
	if clock == nil {
		return nil, ErrNilClock
	}
	return &Enforcer{
		verifier:  verifier,
		authority: authority,
		executor:  executor,
		clock:     clock,
	}, nil
}

// Execute is the single canonical entry point for protected tool execution.
// It enforces the strict sequential pipeline:
//  1. Validate enforcer initialization.
//  2. Verify untrusted compact token via injected GrantVerifier.
//  3. Bind untrusted ExecutionAttempt to verified grant via executionattempt.Bind.
//  4. Defensively validate bound object sanity and non-zero invariants.
//  5. Check pre-claim strict execution-boundary expiration (now < bound.ExpiresAt()).
//  6. Check pre-claim context cancellation (ctx.Err() == nil).
//  7. Construct immutable BoundToolCall carrying canonical payload and verified metadata.
//  8. Claim atomic single-use execution authority via ExecutionAuthority.
//  9. Interpret claim result (ClaimAcquired required to proceed).
//
// 10. Check post-claim context cancellation (ctx.Err() == nil).
// 11. Check post-claim strict execution-boundary expiration (now < call.ExpiresAt()).
// 12. Invoke injected ToolExecutor.
//
// If any step prior to step 12 fails, the ToolExecutor is NEVER invoked.
func (e *Enforcer) Execute(
	ctx context.Context,
	compactToken string,
	attempt executionattempt.ExecutionAttempt,
) (ToolResult, error) {
	if e == nil || e.verifier == nil || e.authority == nil || e.executor == nil || e.clock == nil {
		return ToolResult{}, ErrInvalidEnforcer
	}

	// 1. Verify untrusted compact execution-grant token
	verifiedGrant, err := e.verifier.Verify(ctx, compactToken)
	if err != nil {
		return ToolResult{}, ErrVerificationFailed
	}

	// 2. Canonical exact binding between verified grant and untrusted execution attempt
	bound, err := executionattempt.Bind(verifiedGrant, attempt)
	if err != nil {
		return ToolResult{}, ErrBindingFailed
	}

	// 3. Defensive bound-object sanity check (prevent zero-value / partial bound object misuse)
	if err := validateBoundAttempt(bound); err != nil {
		return ToolResult{}, err
	}

	// 4. Pre-claim strict execution-boundary expiry check
	now := e.clock.Now().UTC()
	if !now.Before(bound.ExpiresAt().UTC()) {
		return ToolResult{}, ErrExecutionGrantExpired
	}

	// 5. Pre-claim context cancellation check
	if ctx.Err() != nil {
		return ToolResult{}, ErrExecutionCanceled
	}

	// 6. Construct immutable BoundToolCall from bound object ONLY (never from untrusted attempt)
	call := BoundToolCall{
		grantID:              bound.GrantID(),
		organizationID:       bound.OrganizationID(),
		agentID:              bound.AgentID(),
		governedActionID:     bound.GovernedActionID(),
		governanceDecisionID: bound.GovernanceDecisionID(),
		toolName:             bound.ToolName(),
		operationName:        bound.OperationName(),
		payloadHash:          bound.PayloadHash(),
		expiresAt:            bound.ExpiresAt(),
		payload:              bound.Payload(), // defensive clone of RFC 8785 canonical bytes
	}

	// 7. Atomic replay-admission claim against ExecutionAuthority
	claimResult, err := e.authority.Claim(ctx, call)
	if err != nil {
		return ToolResult{}, ErrExecutionAuthorityFailed
	}

	switch claimResult {
	case ClaimAcquired:
		// Succeeded: proceed toward execution
	case ClaimReplay:
		return ToolResult{}, ErrExecutionReplay
	default:
		return ToolResult{}, ErrExecutionAuthorityFailed
	}

	// 8. Post-claim context cancellation check
	if ctx.Err() != nil {
		return ToolResult{}, ErrExecutionCanceled
	}

	// 9. Post-claim strict execution-boundary expiry check
	now = e.clock.Now().UTC()
	if !now.Before(call.ExpiresAt().UTC()) {
		return ToolResult{}, ErrExecutionGrantExpired
	}

	// 10. Invoke downstream tool executor exactly once
	res, err := e.executor.Execute(ctx, call)
	if err != nil {
		return ToolResult{}, ErrToolExecutionFailed
	}

	return ToolResult{
		Payload: bytes.Clone(res.Payload),
	}, nil
}

// validateBoundAttempt defensively checks that the bound attempt is complete and non-zero.
func validateBoundAttempt(bound executionattempt.GrantBoundExecutionAttempt) error {
	if bound.GrantID() == uuid.Nil ||
		bound.OrganizationID() == uuid.Nil ||
		bound.AgentID() == uuid.Nil ||
		bound.GovernedActionID() == uuid.Nil ||
		bound.GovernanceDecisionID() == uuid.Nil ||
		strings.TrimSpace(bound.ToolName()) == "" ||
		strings.TrimSpace(bound.OperationName()) == "" ||
		strings.TrimSpace(bound.PayloadHash()) == "" ||
		bound.ExpiresAt().IsZero() ||
		len(bound.Payload()) == 0 {
		return ErrInvalidBoundAttempt
	}
	return nil
}
