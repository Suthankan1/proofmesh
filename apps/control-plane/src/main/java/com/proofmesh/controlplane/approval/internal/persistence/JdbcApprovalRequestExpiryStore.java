package com.proofmesh.controlplane.approval.internal.persistence;

import com.proofmesh.controlplane.approval.ApprovalRequestExpiryStore;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Repository
class JdbcApprovalRequestExpiryStore
        implements ApprovalRequestExpiryStore {

    private final JdbcTemplate jdbcTemplate;

    JdbcApprovalRequestExpiryStore(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate must not be null"
                );
    }

    @Override
    public boolean expirePending(
            UUID organizationId,
            UUID approvalRequestId,
            Instant expiredAt
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
                expiredAt,
                "expiredAt must not be null"
        );

        OffsetDateTime databaseExpiredAt =
                OffsetDateTime.ofInstant(
                        expiredAt,
                        ZoneOffset.UTC
                );

        int affectedRows =
                jdbcTemplate.update(
                        """
                        UPDATE proofmesh.approval_requests
                        SET status = 'EXPIRED',
                            expired_at = ?
                        WHERE organization_id = ?
                          AND id = ?
                          AND status = 'PENDING'
                          AND expires_at <= ?
                        """,
                        databaseExpiredAt,
                        organizationId,
                        approvalRequestId,
                        databaseExpiredAt
                );

        if (affectedRows == 1) {
            return true;
        }

        if (affectedRows == 0) {
            return false;
        }

        throw new ApprovalRequestIntegrityException(
                "approval expiry affected an unexpected number of rows: "
                        + affectedRows
        );
    }
}