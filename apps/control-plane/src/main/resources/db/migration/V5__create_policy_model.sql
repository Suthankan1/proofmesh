CREATE TABLE policies (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL
        REFERENCES organizations(id),
    name VARCHAR(160) NOT NULL
        CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_policies_id_organization
        UNIQUE (id, organization_id)
);

CREATE INDEX idx_policies_organization_created_at
    ON policies(
        organization_id,
        created_at DESC
    );


CREATE TABLE policy_versions (
    id UUID PRIMARY KEY,

    policy_id UUID NOT NULL,
    organization_id UUID NOT NULL,

    version_number INTEGER NOT NULL
        CHECK (version_number >= 1),

    state VARCHAR(32) NOT NULL
        CHECK (
            state IN (
                'DRAFT',
                'PUBLISHED'
            )
        ),

    definition JSONB NOT NULL
        CHECK (
            jsonb_typeof(definition) = 'object'
        )
        CHECK (
            definition ? 'rules'
        )
        CHECK (
            jsonb_typeof(
                definition -> 'rules'
            ) = 'array'
        ),

    definition_hash VARCHAR(64),

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    published_at TIMESTAMPTZ,

    CONSTRAINT fk_policy_versions_policy_organization
        FOREIGN KEY (
            policy_id,
            organization_id
        )
        REFERENCES policies(
            id,
            organization_id
        ),

    CONSTRAINT uq_policy_versions_policy_number
        UNIQUE (
            policy_id,
            version_number
        ),

    CONSTRAINT uq_policy_versions_id_organization
        UNIQUE (
            id,
            organization_id
        ),

    CONSTRAINT chk_policy_versions_definition_hash
        CHECK (
            definition_hash IS NULL
            OR definition_hash
                ~ '^[0-9a-f]{64}$'
        ),

    CONSTRAINT chk_policy_versions_publication_state
        CHECK (
            (
                state = 'DRAFT'
                AND definition_hash IS NULL
                AND published_at IS NULL
            )
            OR
            (
                state = 'PUBLISHED'
                AND definition_hash IS NOT NULL
                AND published_at IS NOT NULL
            )
        ),

    CONSTRAINT chk_policy_versions_publication_time
        CHECK (
            published_at IS NULL
            OR published_at >= created_at
        )
);

CREATE INDEX idx_policy_versions_policy_version
    ON policy_versions(
        policy_id,
        version_number DESC
    );

CREATE INDEX idx_policy_versions_organization_state
    ON policy_versions(
        organization_id,
        state,
        created_at DESC
    );


CREATE FUNCTION enforce_policy_version_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.state = 'PUBLISHED' THEN
            RAISE EXCEPTION
                USING
                    ERRCODE = '23000',
                    MESSAGE =
                        'published policy versions cannot be deleted';
        END IF;

        RETURN OLD;
    END IF;

    IF OLD.state = 'PUBLISHED' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'published policy versions are immutable';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.policy_id
            IS DISTINCT FROM OLD.policy_id
        OR NEW.organization_id
            IS DISTINCT FROM OLD.organization_id
        OR NEW.version_number
            IS DISTINCT FROM OLD.version_number
        OR NEW.created_at
            IS DISTINCT FROM OLD.created_at THEN

        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'policy version identity fields are immutable';
    END IF;

    IF OLD.state = 'DRAFT'
        AND NEW.state = 'PUBLISHED'
        AND NEW.definition
            IS DISTINCT FROM OLD.definition THEN

        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'policy definition must be persisted before publication';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_policy_versions_immutable_update
BEFORE UPDATE
ON policy_versions
FOR EACH ROW
EXECUTE FUNCTION
    enforce_policy_version_immutability();


CREATE TRIGGER trg_policy_versions_immutable_delete
BEFORE DELETE
ON policy_versions
FOR EACH ROW
EXECUTE FUNCTION
    enforce_policy_version_immutability();