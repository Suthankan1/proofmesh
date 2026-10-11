CREATE TABLE agent_runtime_limits (
    organization_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    window_minute BIGINT NOT NULL,
    requests INTEGER NOT NULL CHECK (requests > 0),
    PRIMARY KEY (organization_id,agent_id),
    FOREIGN KEY (organization_id,agent_id) REFERENCES agents(organization_id,id)
);

CREATE TABLE agent_runtime_audit (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('ADMITTED','RATE_LIMITED')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id,agent_id) REFERENCES agents(organization_id,id)
);
CREATE INDEX idx_agent_runtime_audit_tenant_time ON agent_runtime_audit(organization_id,recorded_at,id);

CREATE FUNCTION reject_agent_runtime_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Agent runtime audit is append-only';
END;
$$;
CREATE TRIGGER agent_runtime_audit_immutable BEFORE UPDATE OR DELETE ON agent_runtime_audit
FOR EACH ROW EXECUTE FUNCTION reject_agent_runtime_audit_mutation();
