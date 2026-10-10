package executionevidence

import (
	"context"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
	"github.com/jackc/pgx/v5/pgxpool"
)

// PostgresStore shares the durable claim database without owning its pool.
type PostgresStore struct{ pool *pgxpool.Pool }

func NewPostgresStore(pool *pgxpool.Pool) (*PostgresStore, error) {
	if pool == nil {
		return nil, ErrEvidenceUnavailable
	}
	return &PostgresStore{pool: pool}, nil
}

// Match all immutable binding dimensions; neither tenant nor claim can be substituted.
const binding = `grant_id=$1 AND organization_id=$2 AND agent_id=$3 AND governed_action_id=$4
 AND governance_decision_id=$5 AND tool_name=$6 AND operation_name=$7 AND payload_hash=$8`

func bindingArgs(c pep.BoundToolCall) []any {
	return []any{c.GrantID(), c.OrganizationID(), c.AgentID(), c.GovernedActionID(), c.GovernanceDecisionID(), c.ToolName(), c.OperationName(), c.PayloadHash()}
}
func (s *PostgresStore) Start(ctx context.Context, c pep.BoundToolCall) error {
	if s == nil || s.pool == nil {
		return ErrEvidenceUnavailable
	}
	tag, err := s.pool.Exec(ctx, `UPDATE execution_grant_claims SET execution_status='STARTED',started_at=clock_timestamp()
 WHERE `+binding+` AND execution_status='ADMITTED' AND expires_at>clock_timestamp()`, bindingArgs(c)...)
	if err != nil || tag.RowsAffected() != 1 {
		return ErrEvidenceUnavailable
	}
	return nil
}
func (s *PostgresStore) Finish(ctx context.Context, c pep.BoundToolCall, o Outcome) error {
	if s == nil || s.pool == nil {
		return ErrEvidenceUnavailable
	}
	args := append(bindingArgs(c), o.Status, o.ResponseHash, o.ResponseBytes)
	tag, err := s.pool.Exec(ctx, `UPDATE execution_grant_claims SET execution_status=$9,finished_at=clock_timestamp(),
 response_hash=NULLIF($10,''),response_bytes=CASE WHEN $9='RESULT_RECEIVED' THEN $11::bigint ELSE NULL END
 WHERE `+binding+` AND execution_status='STARTED'`, args...)
	if err != nil || tag.RowsAffected() != 1 {
		return ErrEvidenceUnavailable
	}
	return nil
}
