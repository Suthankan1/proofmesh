package com.proofmesh.controlplane.approval.internal;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestExpiredException;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;
import com.proofmesh.controlplane.approval.ApprovalRequestNotFoundException;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.approval.ApprovalRequestResolutionStore;
import com.proofmesh.controlplane.approval.ApprovalRequestResolver;
import com.proofmesh.controlplane.approval.ApprovalResolutionConflictException;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.approval.ApprovalStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
class DefaultApprovalRequestResolver
        implements ApprovalRequestResolver {

    private final ApprovalRequestRepository
            approvalRequestRepository;

    private final ApprovalRequestResolutionStore
            resolutionStore;

    DefaultApprovalRequestResolver(
            ApprovalRequestRepository approvalRequestRepository,
            ApprovalRequestResolutionStore resolutionStore
    ) {
        this.approvalRequestRepository =
                Objects.requireNonNull(
                        approvalRequestRepository,
                        "approvalRequestRepository must not be null"
                );

        this.resolutionStore =
                Objects.requireNonNull(
                        resolutionStore,
                        "resolutionStore must not be null"
                );
    }

    @Override
    @Transactional
    public ApprovalRequest approve(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    ) {
        return resolve(
                organizationId,
                approvalRequestId,
                actorId,
                rationale,
                decidedAt,
                ApprovalStatus.APPROVED
        );
    }

    @Override
    @Transactional
    public ApprovalRequest reject(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    ) {
        return resolve(
                organizationId,
                approvalRequestId,
                actorId,
                rationale,
                decidedAt,
                ApprovalStatus.REJECTED
        );
    }

    private ApprovalRequest resolve(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt,
            ApprovalStatus targetStatus
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

        Objects.requireNonNull(
                targetStatus,
                "targetStatus must not be null"
        );

        /*
         * Do not pre-read the approval request here.
         *
         * The database update is the concurrency
         * arbitration point.
         *
         * Pre-reading through JPA and then updating
         * through JdbcTemplate would leave a stale
         * PENDING entity in Hibernate's first-level
         * persistence context.
         */
        boolean updated =
                switch (targetStatus) {
                    case APPROVED ->
                            resolutionStore
                                    .approvePending(
                                            organizationId,
                                            approvalRequestId,
                                            actorId,
                                            rationale,
                                            decidedAt
                                    );

                    case REJECTED ->
                            resolutionStore
                                    .rejectPending(
                                            organizationId,
                                            approvalRequestId,
                                            actorId,
                                            rationale,
                                            decidedAt
                                    );

                    default ->
                            throw new ApprovalRequestIntegrityException(
                                    "unsupported human approval resolution status"
                            );
                };

        /*
         * This is now the first JPA load in this
         * transaction, so it reflects the authoritative
         * database state rather than a stale managed
         * PENDING entity.
         */
        ApprovalRequest authoritative =
                load(
                        organizationId,
                        approvalRequestId
                );

        if (updated) {
            if (!hasSameHumanResolution(
                    authoritative,
                    targetStatus,
                    actorId,
                    rationale
            )) {
                throw new ApprovalRequestIntegrityException(
                        "approval request resolution succeeded but authoritative state does not match"
                );
            }

            return authoritative;
        }

        /*
         * affectedRows == 0 has several legitimate
         * meanings:
         *
         * - another operator already resolved it
         * - this is an idempotent retry
         * - the request has expired
         * - decidedAt predates requestedAt
         *
         * The authoritative row tells us which one.
         */
        if (!authoritative.isPending()) {
            return reconcileExistingResolution(
                    authoritative,
                    targetStatus,
                    actorId,
                    rationale
            );
        }

        if (decidedAt.isBefore(
                authoritative.requestedAt()
        )) {
            throw new IllegalArgumentException(
                    "approval resolution cannot predate approval request"
            );
        }

        if (authoritative.isExpiredAt(
                decidedAt
        )) {
            throw new ApprovalRequestExpiredException(
                    "approval request has expired"
            );
        }

        throw new ApprovalRequestIntegrityException(
                "approval request remained pending after atomic resolution attempt"
        );
    }

    private ApprovalRequest reconcileExistingResolution(
            ApprovalRequest authoritative,
            ApprovalStatus targetStatus,
            ApprovalActorId actorId,
            ApprovalRationale rationale
    ) {
        if (authoritative.isExpired()) {
            throw new ApprovalRequestExpiredException(
                    "approval request has expired"
            );
        }

        if (hasSameHumanResolution(
                authoritative,
                targetStatus,
                actorId,
                rationale
        )) {
            return authoritative;
        }

        throw new ApprovalResolutionConflictException(
                "approval request has already been resolved differently"
        );
    }

    private boolean hasSameHumanResolution(
            ApprovalRequest request,
            ApprovalStatus targetStatus,
            ApprovalActorId actorId,
            ApprovalRationale rationale
    ) {
        if (targetStatus == ApprovalStatus.APPROVED
                && request.state()
                        instanceof ApprovalState.Approved approved) {

            return approved.actorId()
                    .equals(
                            actorId
                    )
                    && approved.rationale()
                            .equals(
                                    rationale
                            );
        }

        if (targetStatus == ApprovalStatus.REJECTED
                && request.state()
                        instanceof ApprovalState.Rejected rejected) {

            return rejected.actorId()
                    .equals(
                            actorId
                    )
                    && rejected.rationale()
                            .equals(
                                    rationale
                            );
        }

        return false;
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