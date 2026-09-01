ALTER TABLE proofmesh.governed_actions
    ADD CONSTRAINT uq_governed_actions_id_organization
    UNIQUE (
        id,
        organization_id
    );


CREATE FUNCTION proofmesh.validate_decision_reason_codes(
    reason_codes JSONB
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    reason JSONB;
    reason_code TEXT;
    seen_codes TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF jsonb_typeof(reason_codes) <> 'array' THEN
        RETURN FALSE;
    END IF;

    IF jsonb_array_length(reason_codes) = 0 THEN
        RETURN FALSE;
    END IF;

    FOR reason IN
        SELECT value
        FROM jsonb_array_elements(reason_codes)
    LOOP
        IF jsonb_typeof(reason) <> 'string' THEN
            RETURN FALSE;
        END IF;

        reason_code :=
            reason #>> '{}';

        IF reason_code
            !~ '^[A-Z][A-Z0-9_]{1,63}$' THEN
            RETURN FALSE;
        END IF;

        IF reason_code = ANY(seen_codes) THEN
            RETURN FALSE;
        END IF;

        seen_codes :=
            array_append(
                seen_codes,
                reason_code
            );
    END LOOP;

    RETURN TRUE;
END;
$$;


CREATE TABLE proofmesh.governance_decisions (
    id UUID PRIMARY KEY,

    organization_id UUID NOT NULL,

    governed_action_id UUID NOT NULL,

    policy_version_id UUID NOT NULL,

    matched_policy_rule_id UUID,

    outcome VARCHAR(32) NOT NULL
        CHECK (
            outcome IN (
                'ALLOW',
                'DENY',
                'REQUIRE_APPROVAL'
            )
        ),

    risk_score SMALLINT NOT NULL
        CHECK (
            risk_score >= 0
            AND risk_score <= 100
        ),

    reason_codes JSONB NOT NULL
        CONSTRAINT chk_governance_decisions_reason_codes
        CHECK (
            proofmesh.validate_decision_reason_codes(
                reason_codes
            )
        ),

    decided_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_governance_decisions_action_organization
        FOREIGN KEY (
            governed_action_id,
            organization_id
        )
        REFERENCES proofmesh.governed_actions(
            id,
            organization_id
        ),

    CONSTRAINT fk_governance_decisions_policy_version_organization
        FOREIGN KEY (
            policy_version_id,
            organization_id
        )
        REFERENCES proofmesh.policy_versions(
            id,
            organization_id
        ),

    CONSTRAINT uq_governance_decisions_action
        UNIQUE (
            organization_id,
            governed_action_id
        ),

    CONSTRAINT uq_governance_decisions_id_organization
        UNIQUE (
            id,
            organization_id
        ),

    CONSTRAINT chk_governance_decisions_rule_provenance
        CHECK (
            outcome = 'DENY'
            OR matched_policy_rule_id IS NOT NULL
        )
);


CREATE INDEX idx_governance_decisions_organization_decided_at
    ON proofmesh.governance_decisions(
        organization_id,
        decided_at DESC
    );


CREATE INDEX idx_governance_decisions_policy_version
    ON proofmesh.governance_decisions(
        policy_version_id,
        decided_at DESC
    );


CREATE FUNCTION proofmesh.validate_governance_decision_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    referenced_policy_state VARCHAR(32);
    referenced_definition JSONB;
BEGIN
    SELECT
        state,
        definition
    INTO
        referenced_policy_state,
        referenced_definition
    FROM proofmesh.policy_versions
    WHERE id = NEW.policy_version_id
      AND organization_id =
            NEW.organization_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23503',
                MESSAGE =
                    'governance decision references an unknown policy version';
    END IF;

    IF referenced_policy_state
            <> 'PUBLISHED' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'governance decisions must reference a published policy version';
    END IF;

    IF NEW.matched_policy_rule_id
            IS NOT NULL
        AND NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(
                    referenced_definition
                        -> 'rules'
                 ) AS rule
            WHERE rule ->> 'id'
                    =
                    NEW.matched_policy_rule_id::TEXT
        ) THEN

        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'matched policy rule does not exist in the referenced policy version';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_governance_decisions_validate_provenance
BEFORE INSERT
ON proofmesh.governance_decisions
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.validate_governance_decision_provenance();


CREATE FUNCTION proofmesh.enforce_governance_decision_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'governance decisions are immutable';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'governance decisions cannot be deleted';
    END IF;

    RETURN NULL;
END;
$$;


CREATE TRIGGER trg_governance_decisions_immutable_update
BEFORE UPDATE
ON proofmesh.governance_decisions
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.enforce_governance_decision_immutability();


CREATE TRIGGER trg_governance_decisions_immutable_delete
BEFORE DELETE
ON proofmesh.governance_decisions
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.enforce_governance_decision_immutability();