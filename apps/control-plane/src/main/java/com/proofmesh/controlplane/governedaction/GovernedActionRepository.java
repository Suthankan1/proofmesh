package com.proofmesh.controlplane.governedaction;

import java.util.Optional;
import java.util.UUID;

public interface GovernedActionRepository {

    GovernedActionInsertResult insertIfAbsent(
            GovernedAction governedAction
    );

    Optional<GovernedAction>
    findByIdAndOrganizationId(
            UUID governedActionId,
            UUID organizationId
    );

    Optional<GovernedAction>
    findByOrganizationIdAndAgentIdAndIdempotencyKey(
            UUID organizationId,
            UUID agentId,
            IdempotencyKey idempotencyKey
    );
}