CREATE FUNCTION proofmesh.validate_governance_decision_risk_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    authoritative_risk_score SMALLINT;
BEGIN
    /*
     * Do not mask the existing governed-action foreign key.
     *
     * If the action does not belong to this organization, allow the
     * governance_decisions FK to reject the row for the correct reason.
     */
    IF NOT EXISTS (
        SELECT 1
        FROM proofmesh.governed_actions
        WHERE id = NEW.governed_action_id
          AND organization_id = NEW.organization_id
    ) THEN
        RETURN NEW;
    END IF;

    SELECT risk_score
    INTO authoritative_risk_score
    FROM proofmesh.risk_assessments
    WHERE organization_id = NEW.organization_id
      AND governed_action_id = NEW.governed_action_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23503',
                MESSAGE =
                    'governance decision requires an authoritative risk assessment';
    END IF;

    IF NEW.risk_score
            <> authoritative_risk_score THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'governance decision risk score does not match the authoritative risk assessment';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_governance_decisions_validate_risk_provenance
BEFORE INSERT
ON proofmesh.governance_decisions
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.validate_governance_decision_risk_provenance();