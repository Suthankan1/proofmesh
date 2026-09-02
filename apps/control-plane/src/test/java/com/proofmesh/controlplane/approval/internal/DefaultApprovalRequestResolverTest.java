package com.proofmesh.controlplane.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestExpiredException;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.approval.ApprovalRequestResolutionStore;
import com.proofmesh.controlplane.approval.ApprovalResolutionConflictException;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class DefaultApprovalRequestResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "81000000-0000-0000-0000-000000000001"
            );

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString(
                    "82000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "83000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNANCE_DECISION_ID =
            UUID.fromString(
                    "84000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "85000000-0000-0000-0000-000000000001"
            );

    private static final Instant REQUESTED_AT =
            Instant.parse(
                    "2026-09-02T02:00:00Z"
            );

    private static final Instant EXPIRES_AT =
            Instant.parse(
                    "2026-09-02T02:15:00Z"
            );

    private static final Instant DECIDED_AT =
            Instant.parse(
                    "2026-09-02T02:05:00Z"
            );

    private static final ApprovalActorId ACTOR =
            new ApprovalActorId(
                    "operator-subject-001"
            );

    private static final ApprovalRationale RATIONALE =
            new ApprovalRationale(
                    "Reviewed the exact governed request."
            );

    private ApprovalRequestRepository repository;

    private ApprovalRequestResolutionStore
            resolutionStore;

    private DefaultApprovalRequestResolver resolver;

    @BeforeEach
    void setUp() {
        repository =
                mock(
                        ApprovalRequestRepository.class
                );

        resolutionStore =
                mock(
                        ApprovalRequestResolutionStore.class
                );

        resolver =
                new DefaultApprovalRequestResolver(
                        repository,
                        resolutionStore
                );
    }

    @Test
    void approvesPendingRequest() {
        ApprovalRequest approved =
                pending().approve(
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                );

        when(
                resolutionStore.approvePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                )
        ).thenReturn(
                true
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(
                        approved
                )
        );

        ApprovalRequest result =
                resolver.approve(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                );

        assertThat(result)
                .isEqualTo(
                        approved
                );
    }

    @Test
    void rejectsPendingRequest() {
        ApprovalRequest rejected =
                pending().reject(
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                );

        when(
                resolutionStore.rejectPending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                )
        ).thenReturn(
                true
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(
                        rejected
                )
        );

        assertThat(
                resolver.reject(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                )
        ).isEqualTo(
                rejected
        );
    }

    @Test
    void exactApprovedRetryReturnsAuthoritativeResolution() {
        ApprovalRequest approved =
                pending().approve(
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                );

        Instant retriedAt =
                DECIDED_AT.plusSeconds(
                        30
                );

        when(
                resolutionStore.approvePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        retriedAt
                )
        ).thenReturn(
                false
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(
                        approved
                )
        );

        ApprovalRequest result =
                resolver.approve(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        retriedAt
                );

        assertThat(result)
                .isEqualTo(
                        approved
                );

        verify(
                resolutionStore
        ).approvePending(
                ORGANIZATION_ID,
                APPROVAL_REQUEST_ID,
                ACTOR,
                RATIONALE,
                retriedAt
        );
    }

    @Test
    void opposingResolutionAgainstApprovedRequestConflicts() {
        ApprovalRequest approved =
                pending().approve(
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                );

        Instant retriedAt =
                DECIDED_AT.plusSeconds(
                        30
                );

        when(
                resolutionStore.rejectPending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        retriedAt
                )
        ).thenReturn(
                false
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(
                        approved
                )
        );

        assertThatThrownBy(
                () -> resolver.reject(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        retriedAt
                )
        )
                .isInstanceOf(
                        ApprovalResolutionConflictException.class
                );
    }

    @Test
    void lostRaceToSameApprovalConverges() {
        ApprovalRequest approved =
                pending().approve(
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                );

        when(
                resolutionStore.approvePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                )
        ).thenReturn(
                false
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(
                        approved
                )
        );

        assertThat(
                resolver.approve(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                )
        ).isEqualTo(
                approved
        );
    }

    @Test
    void lostRaceToOpposingResolutionConflicts() {
        ApprovalRequest rejected =
                pending().reject(
                        new ApprovalActorId(
                                "operator-subject-002"
                        ),
                        new ApprovalRationale(
                                "Rejected after review."
                        ),
                        DECIDED_AT
                );

        when(
                resolutionStore.approvePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                )
        ).thenReturn(
                false
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(
                        rejected
                )
        );

        assertThatThrownBy(
                () -> resolver.approve(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        DECIDED_AT
                )
        )
                .isInstanceOf(
                        ApprovalResolutionConflictException.class
                );
    }

    @Test
    void approvalAtExpirationBoundaryFailsClosed() {
        ApprovalRequest pending =
                pending();

        when(
                resolutionStore.approvePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        EXPIRES_AT
                )
        ).thenReturn(
                false
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(
                        pending
                )
        );

        assertThatThrownBy(
                () -> resolver.approve(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        EXPIRES_AT
                )
        )
                .isInstanceOf(
                        ApprovalRequestExpiredException.class
                );

        verify(
                resolutionStore
        ).approvePending(
                ORGANIZATION_ID,
                APPROVAL_REQUEST_ID,
                ACTOR,
                RATIONALE,
                EXPIRES_AT
        );
    }

    @Test
    void resolutionBeforeRequestTimeFailsClosed() {
        ApprovalRequest pending =
                pending();

        Instant invalidDecisionTime =
                REQUESTED_AT.minusSeconds(
                        1
                );

        when(
                resolutionStore.approvePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        invalidDecisionTime
                )
        ).thenReturn(
                false
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID
                )
        ).thenReturn(
                Optional.of(
                        pending
                )
        );

        assertThatThrownBy(
                () -> resolver.approve(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        ACTOR,
                        RATIONALE,
                        invalidDecisionTime
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "cannot predate"
                );
    }

    private ApprovalRequest pending() {
        return new ApprovalRequest(
                APPROVAL_REQUEST_ID,
                ORGANIZATION_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                AGENT_ID,
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                new RequestPayloadHash(
                        "a".repeat(64)
                ),
                REQUESTED_AT,
                EXPIRES_AT,
                new ApprovalState.Pending()
        );
    }
}