package com.proofmesh.controlplane.approval.internal;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestCreator;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.governedaction.GovernedAction;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
class DefaultApprovalRequestCreator
        implements ApprovalRequestCreator {

    @Override
    public ApprovalRequest create(
            UUID approvalRequestId,
            GovernedAction governedAction,
            GovernanceDecision governanceDecision,
            Instant requestedAt,
            Instant expiresAt
    ) {
        Objects.requireNonNull(
                approvalRequestId,
                "approvalRequestId must not be null"
        );

        Objects.requireNonNull(
                governedAction,
                "governedAction must not be null"
        );

        Objects.requireNonNull(
                governanceDecision,
                "governanceDecision must not be null"
        );

        Objects.requireNonNull(
                requestedAt,
                "requestedAt must not be null"
        );

        Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );

        if (!governanceDecision.requiresApproval()) {
            throw new IllegalArgumentException(
                    "approval requests require a REQUIRE_APPROVAL governance decision"
            );
        }

        if (!governedAction.organizationId()
                .equals(
                        governanceDecision.organizationId()
                )) {
            throw new IllegalArgumentException(
                    "governed action and governance decision must belong to the same organization"
            );
        }

        if (!governedAction.id()
                .equals(
                        governanceDecision.governedActionId()
                )) {
            throw new IllegalArgumentException(
                    "governance decision must reference the governed action being approved"
            );
        }

        if (requestedAt.isBefore(
                governanceDecision.decidedAt()
        )) {
            throw new IllegalArgumentException(
                    "approval request cannot predate the governance decision"
            );
        }

        return new ApprovalRequest(
            approvalRequestId,
            governedAction.organizationId(),
            governedAction.id(),
            governanceDecision.id(),
            governedAction.agentId(),
            governedAction.toolName(),
            governedAction.operationName(),
            governedAction.requestPayloadHash(),
            requestedAt,
            expiresAt,
            new ApprovalState.Pending()
    );
        }
}