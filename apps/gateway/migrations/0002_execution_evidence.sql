-- +goose Up
-- Older admission rows cannot be retroactively classified as execution outcomes.
ALTER TABLE execution_grant_claims
 ADD COLUMN execution_status TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN',
 ADD COLUMN started_at TIMESTAMPTZ,
 ADD COLUMN finished_at TIMESTAMPTZ,
 ADD COLUMN response_hash TEXT,
 ADD COLUMN response_bytes BIGINT;
ALTER TABLE execution_grant_claims ALTER COLUMN execution_status SET DEFAULT 'ADMITTED';
ALTER TABLE execution_grant_claims ADD CONSTRAINT execution_evidence_shape CHECK (
 (execution_status IN ('LEGACY_UNKNOWN','ADMITTED') AND started_at IS NULL AND finished_at IS NULL AND response_hash IS NULL AND response_bytes IS NULL)
 OR (execution_status='STARTED' AND started_at IS NOT NULL AND finished_at IS NULL AND response_hash IS NULL AND response_bytes IS NULL)
 OR (execution_status='UNKNOWN' AND started_at IS NOT NULL AND finished_at IS NOT NULL AND finished_at >= started_at AND response_hash IS NULL AND response_bytes IS NULL)
 OR (execution_status='RESULT_RECEIVED' AND started_at IS NOT NULL AND finished_at IS NOT NULL AND finished_at >= started_at
     AND response_hash ~ '^[0-9a-f]{64}$' AND response_bytes >= 0 AND response_hash IS NOT NULL AND response_bytes IS NOT NULL)
);
-- +goose StatementBegin
CREATE OR REPLACE FUNCTION protect_execution_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.execution_status <> 'ADMITTED' THEN RAISE EXCEPTION 'new execution claims must begin admitted'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'durable execution claims cannot be deleted'; END IF;
 IF ROW(NEW.grant_id,NEW.organization_id,NEW.agent_id,NEW.governed_action_id,NEW.governance_decision_id,
        NEW.tool_name,NEW.operation_name,NEW.payload_hash,NEW.expires_at,NEW.claimed_at)
 IS DISTINCT FROM ROW(OLD.grant_id,OLD.organization_id,OLD.agent_id,OLD.governed_action_id,OLD.governance_decision_id,
        OLD.tool_name,OLD.operation_name,OLD.payload_hash,OLD.expires_at,OLD.claimed_at) THEN
  RAISE EXCEPTION 'execution claim binding is immutable';
 END IF;
 IF NOT ((OLD.execution_status='ADMITTED' AND NEW.execution_status='STARTED')
     OR (OLD.execution_status='STARTED' AND NEW.execution_status IN ('RESULT_RECEIVED','UNKNOWN') AND NEW.started_at=OLD.started_at)) THEN
  RAISE EXCEPTION 'invalid execution evidence transition';
 END IF;
 RETURN NEW;
END;
$$;
-- +goose StatementEnd
CREATE TRIGGER execution_evidence_immutable BEFORE INSERT OR UPDATE OR DELETE ON execution_grant_claims
 FOR EACH ROW EXECUTE FUNCTION protect_execution_evidence();
