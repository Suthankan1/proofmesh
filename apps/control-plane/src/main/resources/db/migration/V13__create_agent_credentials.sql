-- Opaque credentials contain 256 random bits; only their SHA-256 digest is retained.
ALTER TABLE agents ADD CONSTRAINT agents_id_organization_unique UNIQUE (id, organization_id);
CREATE TABLE agent_credentials (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    agent_id UUID NOT NULL,
    credential_hash CHAR(64) NOT NULL UNIQUE CHECK (credential_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT agent_credential_tenant_binding FOREIGN KEY (agent_id,organization_id)
        REFERENCES agents(id,organization_id),
    CONSTRAINT agent_credential_validity CHECK (expires_at > created_at),
    CONSTRAINT agent_credential_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE UNIQUE INDEX agent_one_unrevoked_credential ON agent_credentials(organization_id,agent_id)
    WHERE revoked_at IS NULL;
