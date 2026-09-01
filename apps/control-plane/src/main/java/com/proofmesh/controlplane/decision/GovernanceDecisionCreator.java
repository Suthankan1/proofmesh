package com.proofmesh.controlplane.decision;

import java.time.Instant;
import java.util.UUID;

public interface GovernanceDecisionCreator {

    GovernanceDecision create(
            UUID decisionId,
            UUID organizationId,
            UUID governedActionId,
            PolicyEvaluationResult evaluationResult,
            Instant decidedAt
    );
}