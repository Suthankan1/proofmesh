CREATE TABLE agents (
    id UUID PRIMARY KEY,

    organization_id UUID NOT NULL
        REFERENCES organizations(id),

    name VARCHAR(160) NOT NULL,

    status VARCHAR(32) NOT NULL
        CHECK (status IN ('ACTIVE', 'DISABLED')),

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP
);


CREATE INDEX idx_agents_organization_status
    ON agents(organization_id, status);