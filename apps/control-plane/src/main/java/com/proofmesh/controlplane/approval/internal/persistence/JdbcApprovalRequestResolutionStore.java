package com.proofmesh.controlplane.approval.internal.persistence;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;
import com.proofmesh.controlplane.approval.ApprovalRequestResolutionStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Repository
class JdbcApprovalRequestResolutionStore
        implements ApprovalRequestResolutionStore {

    private final JdbcTemplate jdbcTemplate;

    JdbcApprovalRequestResolutionStore(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate must not be null"
                );
    }

    @Override
    public boolean approvePending(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                approvalRequestId,
                "approvalRequestId must not be null"
        );

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

        OffsetDateTime databaseDecidedAt =
                OffsetDateTime.ofInstant(
                        decidedAt,
                        ZoneOffset.UTC
                );

        int affectedRows =
                jdbcTemplate.update(
                        """
                        UPDATE proofmesh.approval_requests
                        SET status = 'APPROVED',
                            actor_id = ?,
                            rationale = ?,
                            decided_at = ?
                        WHERE organization_id = ?
                          AND id = ?
                          AND status = 'PENDING'
                          AND requested_at <= ?
                          AND expires_at > ?
                        """,
                        actorId.value(),
                        rationale.value(),
                        databaseDecidedAt,
                        organizationId,
                        approvalRequestId,
                        databaseDecidedAt,
                        databaseDecidedAt
                );

        return validateAffectedRows(
                affectedRows
        );
    }

    @Override
    public boolean rejectPending(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                approvalRequestId,
                "approvalRequestId must not be null"
        );

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

        OffsetDateTime databaseDecidedAt =
                OffsetDateTime.ofInstant(
                        decidedAt,
                        ZoneOffset.UTC
                );

        int affectedRows =
                jdbcTemplate.update(
                        """
                        UPDATE proofmesh.approval_requests
                        SET status = 'REJECTED',
                            actor_id = ?,
                            rationale = ?,
                            decided_at = ?
                        WHERE organization_id = ?
                          AND id = ?
                          AND status = 'PENDING'
                          AND requested_at <= ?
                          AND expires_at > ?
                        """,
                        actorId.value(),
                        rationale.value(),
                        databaseDecidedAt,
                        organizationId,
                        approvalRequestId,
                        databaseDecidedAt,
                        databaseDecidedAt
                );

        return validateAffectedRows(
                affectedRows
        );
    }

    private boolean validateAffectedRows(
            int affectedRows
    ) {
        if (affectedRows == 1) {
            return true;
        }

        if (affectedRows == 0) {
            return false;
        }

        throw new ApprovalRequestIntegrityException(
                "approval resolution affected an unexpected number of rows: "
                        + affectedRows
        );
    }
}