package pep

import (
	"bytes"
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// ToolResult represents the outcome of a protected tool execution.
// It carries the tool output payload without prescribing transport protocols.
type ToolResult struct {
	Payload []byte
}

// ToolExecutor abstracts the downstream invocation of a protected tool.
// Implementations MUST receive only verified, bound, and strictly non-expired
// execution data encapsulated in BoundToolCall.
type ToolExecutor interface {
	Execute(ctx context.Context, call BoundToolCall) (ToolResult, error)
}

// BoundToolCall represents an immutable execution call snapshot constructed
// strictly from a successfully bound and sanity-checked execution attempt.
// It carries only authoritative, verified dimensions and the RFC 8785 canonical payload.
//
// Possession of this object does not imply replay authority, multi-use authorization,
// or persistence. Replay authority is deferred to slice 08B.
type BoundToolCall struct {
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

// GrantID returns the authoritative ID of the verified execution grant.
func (c BoundToolCall) GrantID() uuid.UUID {
	return c.grantID
}

// OrganizationID returns the authoritative organization ID bound to the call.
func (c BoundToolCall) OrganizationID() uuid.UUID {
	return c.organizationID
}

// AgentID returns the authoritative agent ID bound to the call.
func (c BoundToolCall) AgentID() uuid.UUID {
	return c.agentID
}

// GovernedActionID returns the authoritative governed action ID bound to the call.
func (c BoundToolCall) GovernedActionID() uuid.UUID {
	return c.governedActionID
}

// GovernanceDecisionID returns the authoritative governance decision ID bound to the call.
func (c BoundToolCall) GovernanceDecisionID() uuid.UUID {
	return c.governanceDecisionID
}

// ToolName returns the exact tool name bound to the call.
func (c BoundToolCall) ToolName() string {
	return c.toolName
}

// OperationName returns the exact operation name bound to the call.
func (c BoundToolCall) OperationName() string {
	return c.operationName
}

// PayloadHash returns the lowercase 64-hex SHA-256 hash of the canonical RFC 8785 payload.
func (c BoundToolCall) PayloadHash() string {
	return c.payloadHash
}

// ExpiresAt returns the expiration time of the bound grant.
func (c BoundToolCall) ExpiresAt() time.Time {
	return c.expiresAt
}

// Payload returns a defensive copy of the canonical RFC 8785 payload bytes.
// Mutating the returned slice does not modify the internal call state.
func (c BoundToolCall) Payload() []byte {
	return bytes.Clone(c.payload)
}

// String returns a safe, redacted representation of the bound call,
// omitting sensitive payload bytes and raw token material.
func (c BoundToolCall) String() string {
	return fmt.Sprintf(
		"BoundToolCall{GrantID:%s, OrgID:%s, AgentID:%s, ActionID:%s, DecisionID:%s, Tool:%s, Op:%s, PayloadHash:%s, Exp:%s}",
		c.grantID,
		c.organizationID,
		c.agentID,
		c.governedActionID,
		c.governanceDecisionID,
		c.toolName,
		c.operationName,
		c.payloadHash,
		c.expiresAt.Format(time.RFC3339),
	)
}
