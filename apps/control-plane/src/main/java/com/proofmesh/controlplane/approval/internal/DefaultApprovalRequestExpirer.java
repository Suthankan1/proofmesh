package com.proofmesh.controlplane.approval.internal;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestExpirer;
import com.proofmesh.controlplane.approval.ApprovalRequestExpiryStore;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;
import com.proofmesh.controlplane.approval.ApprovalRequestNotFoundException;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.approval.ApprovalState;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
class DefaultApprovalRequestExpirer
        implements ApprovalRequestExpirer {

    private final ApprovalRequestRepository
            approvalRequestRepository;

    private final ApprovalRequestExpiryStore
            expiryStore;

    DefaultApprovalRequestExpirer(
            ApprovalRequestRepository approvalRequestRepository,
            ApprovalRequestExpiryStore expiryStore
    ) {
        this.approvalRequestRepository =
                Objects.requireNonNull(
                        approvalRequestRepository,
                        "approvalRequestRepository must not be null"
                );

        this.expiryStore =
                Objects.requireNonNull(
                        expiryStore,
                        "expiryStore must not be null"
                );
    }

    @Override
    @Transactional
    public ApprovalRequest expire(
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

        /*
         * Just like human resolution, do not pre-read.
         *
         * PostgreSQL is the lifecycle arbitration
         * point. This also avoids placing a stale
         * PENDING JPA entity into the persistence
         * context before the JDBC update.
         */
        boolean updated =
                expiryStore.expirePending(
                        organizationId,
                        approvalRequestId,
                        expiredAt
                );

        ApprovalRequest authoritative =
                load(
                        organizationId,
                        approvalRequestId
                );

        if (updated) {
            if (!(authoritative.state()
                    instanceof ApprovalState.Expired)) {

                throw new ApprovalRequestIntegrityException(
                        "approval expiry succeeded but authoritative state is not expired"
                );
            }

            return authoritative;
        }

        /*
         * Exact or later expiry retry.
         *
         * Preserve the original expiredAt rather
         * than rewriting historical lifecycle data.
         */
        if (authoritative.isExpired()) {
            return authoritative;
        }

        /*
         * Human resolution already won the race.
         *
         * APPROVED and REJECTED are historical
         * terminal states and must never be
         * rewritten to EXPIRED.
         */
        if (authoritative.isApproved()
                || authoritative.isRejected()) {
            return authoritative;
        }

        /*
         * Still PENDING means the UPDATE predicate
         * failed because the requested expiry time
         * is before the configured boundary, or
         * because something inconsistent happened.
         */
        if (expiredAt.isBefore(
                authoritative.expiresAt()
        )) {
            throw new IllegalArgumentException(
                    "approval request cannot expire before expiresAt"
            );
        }

        throw new ApprovalRequestIntegrityException(
                "approval request remained pending after eligible expiry attempt"
        );
    }

    private ApprovalRequest load(
            UUID organizationId,
            UUID approvalRequestId
    ) {
        return approvalRequestRepository
                .findByOrganizationIdAndId(
                        organizationId,
                        approvalRequestId
                )
                .orElseThrow(
                        () ->
                                new ApprovalRequestNotFoundException(
                                        "approval request does not exist in organization"
                                )
                );
    }
}