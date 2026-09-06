CREATE TABLE execution_grant_claims (
    grant_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    governed_action_id UUID NOT NULL,
    governance_decision_id UUID NOT NULL,
    tool_name TEXT NOT NULL,
    operation_name TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT execution_grant_claims_payload_hash_format
        CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);
