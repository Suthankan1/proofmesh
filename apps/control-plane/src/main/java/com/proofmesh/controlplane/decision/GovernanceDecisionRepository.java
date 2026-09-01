package com.proofmesh.controlplane.decision;

import java.util.Optional;
import java.util.UUID;

public interface GovernanceDecisionRepository {

    Optional<GovernanceDecision> findByOrganizationIdAndId(
            UUID organizationId,
            UUID decisionId
    );

    Optional<GovernanceDecision>
            findByOrganizationIdAndGovernedActionId(
                    UUID organizationId,
                    UUID governedActionId
            );

    GovernanceDecisionInsertResult insertIfAbsent(
            GovernanceDecision decision
    );
}