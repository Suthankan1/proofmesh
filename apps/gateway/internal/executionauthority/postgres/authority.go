package postgres

import (
	"context"
	"errors"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
)

var (
	// ErrNilPool is returned when an Authority is constructed with or operates on a nil pgx pool.
	ErrNilPool = errors.New("executionauthority/postgres: nil pgx pool")

	// ErrInvalidClaim is returned when a claim attempt has zero-value or invalid binding fields.
	ErrInvalidClaim = errors.New("executionauthority/postgres: invalid claim")

	// ErrClaimFailed is returned when a database execution or operational failure occurs.
	ErrClaimFailed = errors.New("executionauthority/postgres: claim failed")
)

// Compile-time interface assertion ensuring *Authority satisfies pep.ExecutionAuthority.
var _ pep.ExecutionAuthority = (*Authority)(nil)

const insertClaimSQL = `
INSERT INTO execution_grant_claims (
    grant_id,
    organization_id,
    agent_id,
    governed_action_id,
    governance_decision_id,
    tool_name,
    operation_name,
    payload_hash,
    expires_at
)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
ON CONFLICT (grant_id) DO NOTHING;
`

// Authority provides a durable PostgreSQL-backed implementation of pep.ExecutionAuthority.
// It relies strictly on PostgreSQL first-writer-wins semantics (INSERT ... ON CONFLICT (grant_id) DO NOTHING)
// to arbitrate single-use execution authority across independent connections, pools, or processes.
type Authority struct {
	pool *pgxpool.Pool
}

// NewAuthority constructs a new PostgreSQL execution authority using the provided connection pool.
// Returns ErrNilPool if pool is nil.
func NewAuthority(pool *pgxpool.Pool) (*Authority, error) {
	if pool == nil {
		return nil, ErrNilPool
	}
	return &Authority{
		pool: pool,
	}, nil
}

// Claim attempts to atomically acquire execution authority for the given bound tool call.
// It returns pep.ClaimAcquired if the grant ID was successfully recorded (RowsAffected == 1),
// pep.ClaimReplay if the grant ID was already claimed (RowsAffected == 0),
// or pep.ClaimResultUnknown with an error on operational or validation failure.
func (a *Authority) Claim(ctx context.Context, call pep.BoundToolCall) (pep.ClaimResult, error) {
	if a == nil || a.pool == nil {
		return pep.ClaimResultUnknown, ErrNilPool
	}

	if err := validateBoundToolCall(call); err != nil {
		return pep.ClaimResultUnknown, err
	}

	tag, err := a.pool.Exec(
		ctx,
		insertClaimSQL,
		call.GrantID(),
		call.OrganizationID(),
		call.AgentID(),
		call.GovernedActionID(),
		call.GovernanceDecisionID(),
		call.ToolName(),
		call.OperationName(),
		call.PayloadHash(),
		call.ExpiresAt(),
	)
	if err != nil {
		return pep.ClaimResultUnknown, ErrClaimFailed
	}

	switch tag.RowsAffected() {
	case 1:
		return pep.ClaimAcquired, nil
	case 0:
		return pep.ClaimReplay, nil
	default:
		return pep.ClaimResultUnknown, ErrClaimFailed
	}
}

func validateBoundToolCall(call pep.BoundToolCall) error {
	if call.GrantID() == uuid.Nil ||
		call.OrganizationID() == uuid.Nil ||
		call.AgentID() == uuid.Nil ||
		call.GovernedActionID() == uuid.Nil ||
		call.GovernanceDecisionID() == uuid.Nil ||
		strings.TrimSpace(call.ToolName()) == "" ||
		strings.TrimSpace(call.OperationName()) == "" ||
		strings.TrimSpace(call.PayloadHash()) == "" ||
		call.ExpiresAt().IsZero() {
		return ErrInvalidClaim
	}
	return nil
}
