package com.proofmesh.controlplane.approval;

import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.governedaction.GovernedAction;

import java.time.Instant;
import java.util.UUID;

public interface ApprovalRequestCreator {

    ApprovalRequest create(
            UUID approvalRequestId,
            GovernedAction governedAction,
            GovernanceDecision governanceDecision,
            Instant requestedAt,
            Instant expiresAt
    );
}