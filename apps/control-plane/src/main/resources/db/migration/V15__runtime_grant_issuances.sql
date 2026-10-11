CREATE TABLE runtime_grant_issuances (
    governed_action_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    grant_hash VARCHAR(64) NOT NULL CHECK (grant_hash ~ '^[0-9a-f]{64}$'),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (governed_action_id,organization_id) REFERENCES governed_actions(id,organization_id),
    FOREIGN KEY (organization_id,agent_id) REFERENCES agents(organization_id,id)
);
CREATE INDEX idx_runtime_grant_issuances_tenant ON runtime_grant_issuances(organization_id,issued_at);
CREATE FUNCTION reject_runtime_grant_issuance_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Runtime grant issuance is immutable';
END;
$$;
CREATE TRIGGER runtime_grant_issuances_immutable BEFORE UPDATE OR DELETE ON runtime_grant_issuances
FOR EACH ROW EXECUTE FUNCTION reject_runtime_grant_issuance_mutation();
