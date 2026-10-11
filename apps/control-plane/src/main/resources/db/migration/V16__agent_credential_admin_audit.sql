CREATE TABLE agent_credential_admin_audit (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    actor_id VARCHAR(255) NOT NULL CHECK (length(btrim(actor_id)) > 0),
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('ROTATED','REVOKED')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id,agent_id) REFERENCES agents(organization_id,id)
);
CREATE INDEX idx_agent_credential_admin_audit_tenant_time ON agent_credential_admin_audit(organization_id,recorded_at,id);
CREATE TRIGGER agent_credential_admin_audit_immutable BEFORE UPDATE OR DELETE ON agent_credential_admin_audit
FOR EACH ROW EXECUTE FUNCTION reject_agent_runtime_audit_mutation();
