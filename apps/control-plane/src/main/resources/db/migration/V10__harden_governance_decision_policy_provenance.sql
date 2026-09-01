CREATE OR REPLACE FUNCTION proofmesh.validate_governance_decision_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    referenced_policy_state VARCHAR(32);
    referenced_definition JSONB;
    matched_rule JSONB;
    matched_rule_effect TEXT;
    matched_rule_reason_code TEXT;
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
            IS NOT NULL THEN

        SELECT rule
        INTO matched_rule
        FROM jsonb_array_elements(
                referenced_definition
                    -> 'rules'
             ) AS rule
        WHERE rule ->> 'id'
                =
                NEW.matched_policy_rule_id::TEXT
        LIMIT 1;

        IF matched_rule IS NULL THEN
            RAISE EXCEPTION
                USING
                    ERRCODE = '23514',
                    MESSAGE =
                        'matched policy rule does not exist in the referenced policy version';
        END IF;

        matched_rule_effect =
                matched_rule ->> 'effect';

        IF matched_rule_effect IS NULL
                OR matched_rule_effect
                    <> NEW.outcome::TEXT THEN
            RAISE EXCEPTION
                USING
                    ERRCODE = '23514',
                    MESSAGE =
                        'governance decision outcome does not match the matched policy rule effect';
        END IF;

        matched_rule_reason_code =
                matched_rule ->> 'reasonCode';

        IF matched_rule_reason_code IS NULL
                OR NOT (
                    NEW.reason_codes
                        ? matched_rule_reason_code
                ) THEN
            RAISE EXCEPTION
                USING
                    ERRCODE = '23514',
                    MESSAGE =
                        'governance decision reasons must include the matched policy rule reason code';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;