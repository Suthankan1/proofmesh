CREATE FUNCTION proofmesh.validate_risk_signals(
    signals JSONB
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    signal JSONB;
    signal_code TEXT;
    signal_severity TEXT;
    signal_weight INTEGER;
    signal_explanation TEXT;
    seen_codes TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF jsonb_typeof(signals) <> 'array' THEN
        RETURN FALSE;
    END IF;

    IF jsonb_array_length(signals) = 0 THEN
        RETURN FALSE;
    END IF;

    FOR signal IN
        SELECT value
        FROM jsonb_array_elements(signals)
    LOOP
        IF jsonb_typeof(signal) <> 'object' THEN
            RETURN FALSE;
        END IF;

        IF jsonb_object_length(signal) <> 4 THEN
            RETURN FALSE;
        END IF;

        IF NOT (
            signal ? 'code'
            AND signal ? 'severity'
            AND signal ? 'weight'
            AND signal ? 'explanation'
        ) THEN
            RETURN FALSE;
        END IF;

        IF jsonb_typeof(
                signal -> 'code'
           ) <> 'string' THEN
            RETURN FALSE;
        END IF;

        IF jsonb_typeof(
                signal -> 'severity'
           ) <> 'string' THEN
            RETURN FALSE;
        END IF;

        IF jsonb_typeof(
                signal -> 'weight'
           ) <> 'number' THEN
            RETURN FALSE;
        END IF;

        IF jsonb_typeof(
                signal -> 'explanation'
           ) <> 'string' THEN
            RETURN FALSE;
        END IF;

        signal_code =
                signal ->> 'code';

        signal_severity =
                signal ->> 'severity';

        signal_explanation =
                signal ->> 'explanation';

        IF signal_code
                !~ '^[A-Z][A-Z0-9_]{1,63}$' THEN
            RETURN FALSE;
        END IF;

        IF signal_code = ANY(
                seen_codes
           ) THEN
            RETURN FALSE;
        END IF;

        IF signal_severity NOT IN (
            'LOW',
            'MEDIUM',
            'HIGH',
            'CRITICAL'
        ) THEN
            RETURN FALSE;
        END IF;

        BEGIN
            signal_weight =
                    (signal ->> 'weight')::INTEGER;
        EXCEPTION
            WHEN invalid_text_representation
                OR numeric_value_out_of_range THEN
                RETURN FALSE;
        END;

        IF signal_weight < 0
            OR signal_weight > 100 THEN
            RETURN FALSE;
        END IF;

        IF length(
                btrim(
                    signal_explanation
                )
           ) = 0 THEN
            RETURN FALSE;
        END IF;

        IF length(
                signal_explanation
           ) > 500 THEN
            RETURN FALSE;
        END IF;

        seen_codes =
                array_append(
                        seen_codes,
                        signal_code
                );
    END LOOP;

    RETURN TRUE;
END;
$$;


CREATE TABLE proofmesh.risk_assessments (
    id UUID PRIMARY KEY,

    organization_id UUID NOT NULL,

    governed_action_id UUID NOT NULL,

    logic_version VARCHAR(64) NOT NULL
        CHECK (
            logic_version
                ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
        ),

    risk_score SMALLINT NOT NULL
        CHECK (
            risk_score >= 0
            AND risk_score <= 100
        ),

    signals JSONB NOT NULL
        CONSTRAINT chk_risk_assessments_signals
        CHECK (
            proofmesh.validate_risk_signals(
                signals
            )
        ),

    assessed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_risk_assessments_action_organization
        FOREIGN KEY (
            governed_action_id,
            organization_id
        )
        REFERENCES proofmesh.governed_actions(
            id,
            organization_id
        ),

    CONSTRAINT uq_risk_assessments_action
        UNIQUE (
            organization_id,
            governed_action_id
        ),

    CONSTRAINT uq_risk_assessments_id_organization
        UNIQUE (
            id,
            organization_id
        )
);


CREATE INDEX idx_risk_assessments_organization_assessed_at
    ON proofmesh.risk_assessments(
        organization_id,
        assessed_at DESC
    );


CREATE FUNCTION proofmesh.enforce_risk_assessment_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'risk assessments are immutable';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'risk assessments cannot be deleted';
    END IF;

    RETURN NULL;
END;
$$;


CREATE TRIGGER trg_risk_assessments_immutable_update
BEFORE UPDATE
ON proofmesh.risk_assessments
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.enforce_risk_assessment_immutability();


CREATE TRIGGER trg_risk_assessments_immutable_delete
BEFORE DELETE
ON proofmesh.risk_assessments
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.enforce_risk_assessment_immutability();