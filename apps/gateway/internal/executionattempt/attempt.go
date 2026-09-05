package executionattempt

import (
	"bytes"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// ExecutionAttempt represents an untrusted request to execute a protected tool.
// It carries the claimed governance dimensions and raw payload bytes.
// Before binding, all fields are untrusted and must be strictly validated.
type ExecutionAttempt struct {
	OrganizationID       uuid.UUID
	AgentID              uuid.UUID
	GovernedActionID     uuid.UUID
	GovernanceDecisionID uuid.UUID
	ToolName             string
	OperationName        string
	Payload              []byte
}

// GrantBoundExecutionAttempt represents a successfully bound execution attempt
// whose governed dimensions and RFC 8785 canonical payload hash have been proven
// to exactly match a VerifiedExecutionGrant.
//
// Successful binding proves only that this verified grant matches this execution attempt.
// It does not imply that the attempt is replay-safe, authorized for multi-use, or executed.
//
// Fields are unexported to prevent external packages from constructing forged instances.
type GrantBoundExecutionAttempt struct {
	grantID              uuid.UUID
	organizationID       uuid.UUID
	agentID              uuid.UUID
	governedActionID     uuid.UUID
	governanceDecisionID uuid.UUID
	toolName             string
	operationName        string
	payloadHash          string
	expiresAt            time.Time
	payload              []byte
}

// GrantID returns the ID of the verified execution grant bound to this attempt.
func (a GrantBoundExecutionAttempt) GrantID() uuid.UUID {
	return a.grantID
}

// OrganizationID returns the authoritative organization ID.
func (a GrantBoundExecutionAttempt) OrganizationID() uuid.UUID {
	return a.organizationID
}

// AgentID returns the authoritative agent ID.
func (a GrantBoundExecutionAttempt) AgentID() uuid.UUID {
	return a.agentID
}

// GovernedActionID returns the authoritative governed action ID.
func (a GrantBoundExecutionAttempt) GovernedActionID() uuid.UUID {
	return a.governedActionID
}

// GovernanceDecisionID returns the authoritative governance decision ID.
func (a GrantBoundExecutionAttempt) GovernanceDecisionID() uuid.UUID {
	return a.governanceDecisionID
}

// ToolName returns the exact tool name.
func (a GrantBoundExecutionAttempt) ToolName() string {
	return a.toolName
}

// OperationName returns the exact operation name.
func (a GrantBoundExecutionAttempt) OperationName() string {
	return a.operationName
}

// PayloadHash returns the lowercase 64-hex SHA-256 hash of the canonical RFC 8785 payload.
func (a GrantBoundExecutionAttempt) PayloadHash() string {
	return a.payloadHash
}

// ExpiresAt returns the expiration time of the bound grant.
func (a GrantBoundExecutionAttempt) ExpiresAt() time.Time {
	return a.expiresAt
}

// Payload returns a defensive copy of the RFC 8785 canonicalized payload bytes
// that produced the matching PayloadHash.
// Downstream tool invocation must use this canonical payload.
func (a GrantBoundExecutionAttempt) Payload() []byte {
	return bytes.Clone(a.payload)
}

// String returns a safe, redacted representation of the bound attempt,
// omitting raw payload contents and sensitive token details.
func (a GrantBoundExecutionAttempt) String() string {
	return fmt.Sprintf(
		"GrantBoundExecutionAttempt{GrantID:%s, OrgID:%s, AgentID:%s, ActionID:%s, DecisionID:%s, Tool:%s, Op:%s, PayloadHash:%s, Exp:%s}",
		a.grantID,
		a.organizationID,
		a.agentID,
		a.governedActionID,
		a.governanceDecisionID,
		a.toolName,
		a.operationName,
		a.payloadHash,
		a.expiresAt.Format(time.RFC3339),
	)
}
