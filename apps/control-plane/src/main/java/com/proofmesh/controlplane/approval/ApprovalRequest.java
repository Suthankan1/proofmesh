package com.proofmesh.controlplane.approval;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalRequest(
        UUID id,
        UUID organizationId,
        UUID governedActionId,
        UUID governanceDecisionId,
        UUID agentId,
        ToolName toolName,
        OperationName operationName,
        RequestPayloadHash requestPayloadHash,
        Instant requestedAt,
        Instant expiresAt,
        ApprovalState state
) {

    public ApprovalRequest {
        Objects.requireNonNull(
                id,
                "id must not be null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                governedActionId,
                "governedActionId must not be null"
        );

        Objects.requireNonNull(
                governanceDecisionId,
                "governanceDecisionId must not be null"
        );

        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
        );

        Objects.requireNonNull(
                toolName,
                "toolName must not be null"
        );

        Objects.requireNonNull(
                operationName,
                "operationName must not be null"
        );

        Objects.requireNonNull(
                requestPayloadHash,
                "requestPayloadHash must not be null"
        );

        Objects.requireNonNull(
                requestedAt,
                "requestedAt must not be null"
        );

        Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );

        Objects.requireNonNull(
                state,
                "state must not be null"
        );

        if (!expiresAt.isAfter(
                requestedAt
        )) {
            throw new IllegalArgumentException(
                    "expiresAt must be after requestedAt"
            );
        }

        validateStateTimeline(
                requestedAt,
                expiresAt,
                state
        );
    }

    public ApprovalStatus status() {
        return state.status();
    }

    public boolean isPending() {
        return status()
                == ApprovalStatus.PENDING;
    }

    public boolean isApproved() {
        return status()
                == ApprovalStatus.APPROVED;
    }

    public boolean isRejected() {
        return status()
                == ApprovalStatus.REJECTED;
    }

    public boolean isExpired() {
        return status()
                == ApprovalStatus.EXPIRED;
    }

    public boolean isExpiredAt(
            Instant instant
    ) {
        Objects.requireNonNull(
                instant,
                "instant must not be null"
        );

        return !instant.isBefore(
                expiresAt
        );
    }

    public boolean isApprovedAndValidAt(
            Instant instant
    ) {
        Objects.requireNonNull(
                instant,
                "instant must not be null"
        );

        return isApproved()
                && !isExpiredAt(
                        instant
                );
    }

    public ApprovalRequest approve(
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    ) {
        Objects.requireNonNull(
                actorId,
                "actorId must not be null"
        );

        Objects.requireNonNull(
                rationale,
                "rationale must not be null"
        );

        Objects.requireNonNull(
                decidedAt,
                "decidedAt must not be null"
        );

        requirePending();
        requireDecisionTimeValid(
                decidedAt
        );

        return withState(
                new ApprovalState.Approved(
                        actorId,
                        rationale,
                        decidedAt
                )
        );
    }

    public ApprovalRequest reject(
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    ) {
        Objects.requireNonNull(
                actorId,
                "actorId must not be null"
        );

        Objects.requireNonNull(
                rationale,
                "rationale must not be null"
        );

        Objects.requireNonNull(
                decidedAt,
                "decidedAt must not be null"
        );

        requirePending();
        requireDecisionTimeValid(
                decidedAt
        );

        return withState(
                new ApprovalState.Rejected(
                        actorId,
                        rationale,
                        decidedAt
                )
        );
    }

    public ApprovalRequest expire(
            Instant expiredAt
    ) {
        Objects.requireNonNull(
                expiredAt,
                "expiredAt must not be null"
        );

        requirePending();

        if (expiredAt.isBefore(
                expiresAt
        )) {
            throw new IllegalArgumentException(
                    "approval request cannot expire before expiresAt"
            );
        }

        return withState(
                new ApprovalState.Expired(
                        expiredAt
                )
        );
    }

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException(
                    "approval request is not pending"
            );
        }
    }

    private void requireDecisionTimeValid(
            Instant decidedAt
    ) {
        if (decidedAt.isBefore(
                requestedAt
        )) {
            throw new IllegalArgumentException(
                    "approval decision cannot predate the request"
            );
        }

        if (!decidedAt.isBefore(
                expiresAt
        )) {
            throw new IllegalStateException(
                    "approval request has expired"
            );
        }
    }

    private ApprovalRequest withState(
            ApprovalState newState
    ) {
        return new ApprovalRequest(
                id,
                organizationId,
                governedActionId,
                governanceDecisionId,
                agentId,
                toolName,
                operationName,
                requestPayloadHash,
                requestedAt,
                expiresAt,
                newState
        );
    }

    private static void validateStateTimeline(
            Instant requestedAt,
            Instant expiresAt,
            ApprovalState state
    ) {
        if (state instanceof ApprovalState.Approved approved) {
            validateHumanDecisionTime(
                    requestedAt,
                    expiresAt,
                    approved.decidedAt()
            );
        }

        if (state instanceof ApprovalState.Rejected rejected) {
            validateHumanDecisionTime(
                    requestedAt,
                    expiresAt,
                    rejected.decidedAt()
            );
        }

        if (state instanceof ApprovalState.Expired expired
                && expired.expiredAt()
                        .isBefore(
                                expiresAt
                        )) {
            throw new IllegalArgumentException(
                    "expired state cannot predate expiresAt"
            );
        }
    }

    private static void validateHumanDecisionTime(
            Instant requestedAt,
            Instant expiresAt,
            Instant decidedAt
    ) {
        if (decidedAt.isBefore(
                requestedAt
        )) {
            throw new IllegalArgumentException(
                    "approval decision cannot predate the request"
            );
        }

        if (!decidedAt.isBefore(
                expiresAt
        )) {
            throw new IllegalArgumentException(
                    "approval decision must occur before expiresAt"
            );
        }
    }
}