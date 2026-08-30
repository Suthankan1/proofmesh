ALTER TABLE agents
    ADD CONSTRAINT uq_agents_id_organization
    UNIQUE (id, organization_id);


CREATE TABLE governed_actions (
    id UUID PRIMARY KEY,

    organization_id UUID NOT NULL,

    agent_id UUID NOT NULL,

    idempotency_key VARCHAR(128) NOT NULL,

    tool_name VARCHAR(160) NOT NULL,

    operation_name VARCHAR(160) NOT NULL,

    request_payload JSONB NOT NULL
        CHECK (
            jsonb_typeof(request_payload) = 'object'
        ),

    request_payload_hash VARCHAR(64) NOT NULL
        CHECK (
            request_payload_hash ~ '^[0-9a-f]{64}$'
        ),

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_governed_actions_agent_organization
        FOREIGN KEY (
            agent_id,
            organization_id
        )
        REFERENCES agents(
            id,
            organization_id
        ),

    CONSTRAINT uq_governed_actions_idempotency
        UNIQUE (
            organization_id,
            agent_id,
            idempotency_key
        )
);


CREATE INDEX idx_governed_actions_organization_created_at
    ON governed_actions(
        organization_id,
        created_at DESC
    );


CREATE INDEX idx_governed_actions_agent_created_at
    ON governed_actions(
        agent_id,
        created_at DESC
    );