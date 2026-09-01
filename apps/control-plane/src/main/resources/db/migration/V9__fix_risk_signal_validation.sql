CREATE OR REPLACE FUNCTION proofmesh.validate_risk_signals(
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
    signal_key_count INTEGER;
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

        SELECT COUNT(*)
        INTO signal_key_count
        FROM jsonb_object_keys(signal);

        IF signal_key_count <> 4 THEN
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