package com.proofmesh.controlplane.approval.internal.persistence;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.approval.ApprovalStatus;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class ApprovalRequestJpaMapper {

    ApprovalRequest toDomain(
            ApprovalRequestJpaEntity entity
    ) {
        Objects.requireNonNull(
                entity,
                "entity must not be null"
        );

        try {
            return new ApprovalRequest(
                    entity.id(),
                    entity.organizationId(),
                    entity.governedActionId(),
                    entity.governanceDecisionId(),
                    entity.agentId(),
                    new ToolName(
                            entity.toolName()
                    ),
                    new OperationName(
                            entity.operationName()
                    ),
                    new RequestPayloadHash(
                            entity.requestPayloadHash()
                    ),
                    entity.requestedAt(),
                    entity.expiresAt(),
                    stateFrom(
                            entity
                    )
            );
        } catch (ApprovalRequestIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApprovalRequestIntegrityException(
                    "stored approval request is invalid",
                    exception
            );
        }
    }

    private ApprovalState stateFrom(
            ApprovalRequestJpaEntity entity
    ) {
        final ApprovalStatus status;

        try {
            status =
                    ApprovalStatus.valueOf(
                            entity.status()
                    );
        } catch (RuntimeException exception) {
            throw new ApprovalRequestIntegrityException(
                    "stored approval request has an invalid status",
                    exception
            );
        }

        return switch (status) {
            case PENDING ->
                    pendingState(
                            entity
                    );

            case APPROVED ->
                    approvedState(
                            entity
                    );

            case REJECTED ->
                    rejectedState(
                            entity
                    );

            case EXPIRED ->
                    expiredState(
                            entity
                    );
        };
    }

    private ApprovalState pendingState(
            ApprovalRequestJpaEntity entity
    ) {
        if (entity.actorId() != null
                || entity.rationale() != null
                || entity.decidedAt() != null
                || entity.expiredAt() != null) {
            throw new ApprovalRequestIntegrityException(
                    "stored pending approval request contains resolution metadata"
            );
        }

        return new ApprovalState.Pending();
    }

    private ApprovalState approvedState(
            ApprovalRequestJpaEntity entity
    ) {
        requireHumanResolutionMetadata(
                entity,
                "approved"
        );

        if (entity.expiredAt() != null) {
            throw new ApprovalRequestIntegrityException(
                    "stored approved approval request contains expiration metadata"
            );
        }

        return new ApprovalState.Approved(
                new ApprovalActorId(
                        entity.actorId()
                ),
                new ApprovalRationale(
                        entity.rationale()
                ),
                entity.decidedAt()
        );
    }

    private ApprovalState rejectedState(
            ApprovalRequestJpaEntity entity
    ) {
        requireHumanResolutionMetadata(
                entity,
                "rejected"
        );

        if (entity.expiredAt() != null) {
            throw new ApprovalRequestIntegrityException(
                    "stored rejected approval request contains expiration metadata"
            );
        }

        return new ApprovalState.Rejected(
                new ApprovalActorId(
                        entity.actorId()
                ),
                new ApprovalRationale(
                        entity.rationale()
                ),
                entity.decidedAt()
        );
    }

    private ApprovalState expiredState(
            ApprovalRequestJpaEntity entity
    ) {
        if (entity.actorId() != null
                || entity.rationale() != null
                || entity.decidedAt() != null) {
            throw new ApprovalRequestIntegrityException(
                    "stored expired approval request contains human resolution metadata"
            );
        }

        if (entity.expiredAt() == null) {
            throw new ApprovalRequestIntegrityException(
                    "stored expired approval request is missing expiredAt"
            );
        }

        return new ApprovalState.Expired(
                entity.expiredAt()
        );
    }

    private void requireHumanResolutionMetadata(
            ApprovalRequestJpaEntity entity,
            String status
    ) {
        if (entity.actorId() == null
                || entity.rationale() == null
                || entity.decidedAt() == null) {
            throw new ApprovalRequestIntegrityException(
                    "stored "
                            + status
                            + " approval request is missing human resolution metadata"
            );
        }
    }
}