package executiongrant

import (
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ExpectedGrantType is the strict JWS typ header required for execution grants.
const ExpectedGrantType = "proofmesh-execution-grant+jwt"

var payloadHashRegex = regexp.MustCompile(`^[0-9a-f]{64}$`)

// VerifiedExecutionGrant represents a fully validated, cryptographically proven execution grant.
// Instances of this struct can only be constructed by the Verifier upon successful verification.
type VerifiedExecutionGrant struct {
	GrantID              uuid.UUID
	OrganizationID       uuid.UUID
	AgentID              uuid.UUID
	GovernedActionID     uuid.UUID
	GovernanceDecisionID uuid.UUID

	ToolName      string
	OperationName string
	PayloadHash   string

	Issuer    string
	Audience  string
	IssuedAt  time.Time
	ExpiresAt time.Time
	KeyID     string
}

// String returns a redacted, safe string representation omitting any raw tokens or secrets.
func (g VerifiedExecutionGrant) String() string {
	return fmt.Sprintf(
		"VerifiedExecutionGrant{GrantID:%s, OrgID:%s, AgentID:%s, ActionID:%s, DecisionID:%s, Tool:%s, Op:%s, PayloadHash:%s, Iss:%s, Aud:%s, Iat:%s, Exp:%s, KeyID:%s}",
		g.GrantID,
		g.OrganizationID,
		g.AgentID,
		g.GovernedActionID,
		g.GovernanceDecisionID,
		g.ToolName,
		g.OperationName,
		g.PayloadHash,
		g.Issuer,
		g.Audience,
		g.IssuedAt.Format(time.RFC3339),
		g.ExpiresAt.Format(time.RFC3339),
		g.KeyID,
	)
}

func parseNonNilUUID(s string) (uuid.UUID, error) {
	id, err := uuid.Parse(s)
	if err != nil || id == uuid.Nil {
		return uuid.Nil, ErrInvalidClaim
	}
	return id, nil
}

func validateNonBlankString(s string) error {
	if len(s) == 0 || strings.TrimSpace(s) == "" {
		return ErrInvalidClaim
	}
	return nil
}

func validatePayloadHash(h string) error {
	if !payloadHashRegex.MatchString(h) {
		return ErrInvalidClaim
	}
	return nil
}
