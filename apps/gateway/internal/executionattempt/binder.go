package executionattempt

import (
	"bytes"
	"crypto/subtle"
	"strings"

	"github.com/google/uuid"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/canonicalize"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executiongrant"
)

// Binder provides pure, exact binding between a VerifiedExecutionGrant and an untrusted ExecutionAttempt.
type Binder struct{}

// NewBinder constructs a new Binder.
func NewBinder() *Binder {
	return &Binder{}
}

// Bind delegates to the package-level Bind function.
func (b *Binder) Bind(grant executiongrant.VerifiedExecutionGrant, attempt ExecutionAttempt) (GrantBoundExecutionAttempt, error) {
	return Bind(grant, attempt)
}

// BindExecutionAttempt is an alias for Bind.
func BindExecutionAttempt(grant executiongrant.VerifiedExecutionGrant, attempt ExecutionAttempt) (GrantBoundExecutionAttempt, error) {
	return Bind(grant, attempt)
}

// Bind strictly validates an untrusted ExecutionAttempt and binds it to a VerifiedExecutionGrant
// only when every governed dimension matches exactly and the attempt's RFC 8785 payload hash
// matches the grant's PayloadHash.
//
// Validation rules:
// - All attempt identifiers (OrganizationID, AgentID, GovernedActionID, GovernanceDecisionID) must be non-nil UUIDs.
// - Attempt ToolName and OperationName must not be empty or whitespace-only.
// - Attempt Payload is defensively cloned and canonicalized per RFC 8785 (JCS); non-object, blank, malformed, or duplicate-key payloads fail closed.
// - Exact equality is required on OrganizationID, AgentID, GovernedActionID, GovernanceDecisionID, ToolName, OperationName, and PayloadHash.
// - No trimming, case folding, aliases, fallback, or partial matching is performed.
// - On any dimension mismatch, ErrGrantBindingMismatch is returned without disclosing which dimension failed.
func Bind(grant executiongrant.VerifiedExecutionGrant, attempt ExecutionAttempt) (GrantBoundExecutionAttempt, error) {
	// 1. Untrusted attempt structural validation
	if attempt.OrganizationID == uuid.Nil ||
		attempt.AgentID == uuid.Nil ||
		attempt.GovernedActionID == uuid.Nil ||
		attempt.GovernanceDecisionID == uuid.Nil {
		return GrantBoundExecutionAttempt{}, ErrInvalidExecutionAttempt
	}

	if strings.TrimSpace(attempt.ToolName) == "" || strings.TrimSpace(attempt.OperationName) == "" {
		return GrantBoundExecutionAttempt{}, ErrInvalidExecutionAttempt
	}

	// 2. Verified grant structural sanity check (fail closed if empty/invalid grant provided)
	if grant.GrantID == uuid.Nil ||
		grant.OrganizationID == uuid.Nil ||
		grant.AgentID == uuid.Nil ||
		grant.GovernedActionID == uuid.Nil ||
		grant.GovernanceDecisionID == uuid.Nil ||
		grant.ToolName == "" ||
		grant.OperationName == "" ||
		grant.PayloadHash == "" {
		return GrantBoundExecutionAttempt{}, ErrGrantBindingMismatch
	}

	// 3. Defensive snapshot of untrusted payload to prevent TOCTOU modifications
	payloadSnapshot := bytes.Clone(attempt.Payload)

	// 4. RFC 8785 canonicalization and SHA-256 hashing
	canonical, computedHash, err := canonicalize.CanonicalizeAndHash(payloadSnapshot)
	if err != nil {
		return GrantBoundExecutionAttempt{}, ErrInvalidAttemptPayload
	}

	// 5. Exact equality checks across all 7 governed dimensions
	if attempt.OrganizationID != grant.OrganizationID ||
		attempt.AgentID != grant.AgentID ||
		attempt.GovernedActionID != grant.GovernedActionID ||
		attempt.GovernanceDecisionID != grant.GovernanceDecisionID ||
		attempt.ToolName != grant.ToolName ||
		attempt.OperationName != grant.OperationName ||
		subtle.ConstantTimeCompare([]byte(computedHash), []byte(grant.PayloadHash)) != 1 {
		return GrantBoundExecutionAttempt{}, ErrGrantBindingMismatch
	}

	// 6. Return successfully bound attempt retaining the canonical payload
	return GrantBoundExecutionAttempt{
		grantID:              grant.GrantID,
		organizationID:       grant.OrganizationID,
		agentID:              grant.AgentID,
		governedActionID:     grant.GovernedActionID,
		governanceDecisionID: grant.GovernanceDecisionID,
		toolName:             grant.ToolName,
		operationName:        grant.OperationName,
		payloadHash:          grant.PayloadHash,
		expiresAt:            grant.ExpiresAt,
		payload:              canonical,
	}, nil
}
