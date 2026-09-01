CREATE TABLE proofmesh.approval_requests (
    id UUID PRIMARY KEY,

    organization_id UUID NOT NULL,

    governed_action_id UUID NOT NULL,

    governance_decision_id UUID NOT NULL,

    agent_id UUID NOT NULL,

    tool_name TEXT NOT NULL,

    operation_name TEXT NOT NULL,

    request_payload_hash VARCHAR(64) NOT NULL
        CHECK (
            request_payload_hash
                ~ '^[0-9a-f]{64}$'
        ),

    requested_at TIMESTAMPTZ NOT NULL,

    expires_at TIMESTAMPTZ NOT NULL,

    status VARCHAR(32) NOT NULL
        CHECK (
            status IN (
                'PENDING',
                'APPROVED',
                'REJECTED',
                'EXPIRED'
            )
        ),

    actor_id VARCHAR(255),

    rationale VARCHAR(1000),

    decided_at TIMESTAMPTZ,

    expired_at TIMESTAMPTZ,

    CONSTRAINT fk_approval_requests_action_organization
        FOREIGN KEY (
            governed_action_id,
            organization_id
        )
        REFERENCES proofmesh.governed_actions(
            id,
            organization_id
        ),

    CONSTRAINT fk_approval_requests_decision_organization
        FOREIGN KEY (
            governance_decision_id,
            organization_id
        )
        REFERENCES proofmesh.governance_decisions(
            id,
            organization_id
        ),

    CONSTRAINT uq_approval_requests_decision
        UNIQUE (
            organization_id,
            governance_decision_id
        ),

    CONSTRAINT uq_approval_requests_id_organization
        UNIQUE (
            id,
            organization_id
        ),

    CONSTRAINT chk_approval_requests_time_window
        CHECK (
            expires_at > requested_at
        ),

    CONSTRAINT chk_approval_requests_actor
        CHECK (
            actor_id IS NULL
            OR (
                length(
                    btrim(
                        actor_id
                    )
                ) > 0
                AND length(actor_id) <= 255
            )
        ),

    CONSTRAINT chk_approval_requests_rationale
        CHECK (
            rationale IS NULL
            OR (
                length(
                    btrim(
                        rationale
                    )
                ) > 0
                AND length(rationale) <= 1000
            )
        ),

    CONSTRAINT chk_approval_requests_state
        CHECK (
            (
                status = 'PENDING'
                AND actor_id IS NULL
                AND rationale IS NULL
                AND decided_at IS NULL
                AND expired_at IS NULL
            )
            OR
            (
                status IN (
                    'APPROVED',
                    'REJECTED'
                )
                AND actor_id IS NOT NULL
                AND rationale IS NOT NULL
                AND decided_at IS NOT NULL
                AND expired_at IS NULL
                AND decided_at >= requested_at
                AND decided_at < expires_at
            )
            OR
            (
                status = 'EXPIRED'
                AND actor_id IS NULL
                AND rationale IS NULL
                AND decided_at IS NULL
                AND expired_at IS NOT NULL
                AND expired_at >= expires_at
            )
        )
);


CREATE INDEX idx_approval_requests_action
    ON proofmesh.approval_requests(
        organization_id,
        governed_action_id
    );


CREATE INDEX idx_approval_requests_pending
    ON proofmesh.approval_requests(
        organization_id,
        expires_at,
        requested_at
    )
    WHERE status = 'PENDING';


CREATE FUNCTION proofmesh.validate_approval_request_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    referenced_action_id UUID;
    referenced_decision_outcome VARCHAR(32);
    referenced_decision_at TIMESTAMPTZ;

    referenced_agent_id UUID;
    referenced_tool_name TEXT;
    referenced_operation_name TEXT;
    referenced_request_payload_hash TEXT;
BEGIN
    IF NEW.status
            <> 'PENDING' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'approval requests must be created pending';
    END IF;

    SELECT
        governed_action_id,
        outcome,
        decided_at
    INTO
        referenced_action_id,
        referenced_decision_outcome,
        referenced_decision_at
    FROM proofmesh.governance_decisions
    WHERE id = NEW.governance_decision_id
      AND organization_id =
            NEW.organization_id;

    /*
     * Do not mask the composite FK if the decision
     * does not exist in this organization.
     */
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF referenced_decision_outcome
            <> 'REQUIRE_APPROVAL' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'approval requests require a REQUIRE_APPROVAL governance decision';
    END IF;

    IF NEW.governed_action_id
            <> referenced_action_id THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'approval request must reference the governed action from its governance decision';
    END IF;

    IF NEW.requested_at
            < referenced_decision_at THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'approval request cannot predate its governance decision';
    END IF;

    SELECT
        agent_id,
        tool_name,
        operation_name,
        request_payload_hash
    INTO
        referenced_agent_id,
        referenced_tool_name,
        referenced_operation_name,
        referenced_request_payload_hash
    FROM proofmesh.governed_actions
    WHERE id = NEW.governed_action_id
      AND organization_id =
            NEW.organization_id;

    /*
     * Allow the governed-action FK to provide the
     * authoritative missing/cross-tenant rejection.
     */
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF NEW.agent_id
            IS DISTINCT FROM referenced_agent_id
        OR NEW.tool_name
            IS DISTINCT FROM referenced_tool_name
        OR NEW.operation_name
            IS DISTINCT FROM referenced_operation_name
        OR NEW.request_payload_hash
            IS DISTINCT FROM referenced_request_payload_hash THEN

        RAISE EXCEPTION
            USING
                ERRCODE = '23514',
                MESSAGE =
                    'approval request binding does not match the governed action';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_approval_requests_validate_provenance
BEFORE INSERT
ON proofmesh.approval_requests
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.validate_approval_request_provenance();


CREATE FUNCTION proofmesh.enforce_approval_request_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'approval requests cannot be deleted';
    END IF;

    IF NEW.id
            IS DISTINCT FROM OLD.id
        OR NEW.organization_id
            IS DISTINCT FROM OLD.organization_id
        OR NEW.governed_action_id
            IS DISTINCT FROM OLD.governed_action_id
        OR NEW.governance_decision_id
            IS DISTINCT FROM OLD.governance_decision_id
        OR NEW.agent_id
            IS DISTINCT FROM OLD.agent_id
        OR NEW.tool_name
            IS DISTINCT FROM OLD.tool_name
        OR NEW.operation_name
            IS DISTINCT FROM OLD.operation_name
        OR NEW.request_payload_hash
            IS DISTINCT FROM OLD.request_payload_hash
        OR NEW.requested_at
            IS DISTINCT FROM OLD.requested_at
        OR NEW.expires_at
            IS DISTINCT FROM OLD.expires_at THEN

        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'approval request binding and timing fields are immutable';
    END IF;

    IF OLD.status
            <> 'PENDING' THEN
        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'terminal approval requests are immutable';
    END IF;

    IF NEW.status NOT IN (
            'APPROVED',
            'REJECTED',
            'EXPIRED'
       ) THEN

        RAISE EXCEPTION
            USING
                ERRCODE = '23000',
                MESSAGE =
                    'approval request updates may only resolve a pending request';
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_approval_requests_lifecycle_update
BEFORE UPDATE
ON proofmesh.approval_requests
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.enforce_approval_request_lifecycle();


CREATE TRIGGER trg_approval_requests_lifecycle_delete
BEFORE DELETE
ON proofmesh.approval_requests
FOR EACH ROW
EXECUTE FUNCTION
    proofmesh.enforce_approval_request_lifecycle();