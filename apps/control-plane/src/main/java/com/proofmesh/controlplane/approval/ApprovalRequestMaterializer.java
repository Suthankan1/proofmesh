package com.proofmesh.controlplane.approval;

import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.governedaction.GovernedAction;

import java.time.Instant;

public interface ApprovalRequestMaterializer {

    ApprovalRequest materialize(
            GovernedAction governedAction,
            GovernanceDecision governanceDecision,
            Instant requestedAt,
            Instant expiresAt
    );
}