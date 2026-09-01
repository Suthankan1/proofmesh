CREATE TABLE proofmesh.agent_policy_bindings (
    id UUID PRIMARY KEY,

    organization_id UUID NOT NULL,

    agent_id UUID NOT NULL,

    policy_version_id UUID NOT NULL,

    activated_at TIMESTAMPTZ NOT NULL,

    deactivated_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_agent_policy_bindings_agent_organization
        FOREIGN KEY (
            agent_id,
            organization_id
        )
        REFERENCES proofmesh.agents(
            id,
            organization_id
        ),

    CONSTRAINT fk_agent_policy_bindings_policy_version_organization
        FOREIGN KEY (
            policy_version_id,
            organization_id
        )
        REFERENCES proofmesh.policy_versions(
            id,
            organization_id
        ),

    CONSTRAINT uq_agent_policy_bindings_id_organization
        UNIQUE (
            id,
            organization_id
        ),

    CONSTRAINT chk_agent_policy_bindings_lifecycle
        CHECK (
            deactivated_at IS NULL
            OR deactivated_at >= activated_at
        )
);


CREATE UNIQUE INDEX uq_agent_policy_bindings_active_agent
    ON proofmesh.agent_policy_bindings(
        organization_id,
        agent_id
    )
    WHERE deactivated_at IS NULL;


CREATE INDEX idx_agent_policy_bindings_agent_history
    ON proofmesh.agent_policy_bindings(
        organization_id,
        agent_id,
        activated_at DESC
    );


CREATE INDEX idx_agent_policy_bindings_policy_version
    ON proofmesh.agent_policy_bindings(
        policy_version_id,
        activated_at DESC
    );


CREATE FUNCTION proofmesh.validate_agent_policy_binding()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    referenced_policy_state VARCHAR(32);
    referenced_published_at TIMESTAMPTZ;
BEGIN
    SELECT
        state,
        published_at
    INTO
        referenced_policy_state,
        referenced_published_at
    FROM proofmesh.policy_versions
    WHERE id = NEW.policy_version_id
      AND organization_id =
            NEW.organization_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23503',
                MESSAGE =
                    'agent policy binding references an unknown policy version';
    END IF;

    IF referenced_policy_state
            <> 'PUBLISHED' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'agent policy bindings must reference published policy versions';
    END IF;

    IF referenced_published_at
            IS NULL THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'published policy version is missing publication time';
    END IF;

    IF NEW.activated_at
            < referenced_published_at THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'policy binding cannot become active before policy publication';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_agent_policy_bindings_validate
BEFORE INSERT
ON proofmesh.agent_policy_bindings
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.validate_agent_policy_binding();


CREATE FUNCTION proofmesh.enforce_agent_policy_binding_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'agent policy bindings cannot be deleted';
    END IF;

    IF NEW.id
            IS DISTINCT FROM OLD.id
        OR NEW.organization_id
            IS DISTINCT FROM OLD.organization_id
        OR NEW.agent_id
            IS DISTINCT FROM OLD.agent_id
        OR NEW.policy_version_id
            IS DISTINCT FROM OLD.policy_version_id
        OR NEW.activated_at
            IS DISTINCT FROM OLD.activated_at
        OR NEW.created_at
            IS DISTINCT FROM OLD.created_at THEN

        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'agent policy binding identity and activation fields are immutable';
    END IF;

    IF OLD.deactivated_at
            IS NOT NULL THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'deactivated agent policy bindings are immutable';
    END IF;

    IF NEW.deactivated_at
            IS NULL THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'agent policy binding updates may only deactivate an active binding';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_agent_policy_bindings_lifecycle_update
BEFORE UPDATE
ON proofmesh.agent_policy_bindings
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.enforce_agent_policy_binding_lifecycle();


CREATE TRIGGER trg_agent_policy_bindings_lifecycle_delete
BEFORE DELETE
ON proofmesh.agent_policy_bindings
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.enforce_agent_policy_binding_lifecycle();