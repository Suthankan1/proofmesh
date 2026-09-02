package com.proofmesh.controlplane.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestExpiryStore;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;
import com.proofmesh.controlplane.approval.ApprovalRequestNotFoundException;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
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

class DefaultApprovalRequestExpirerTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "91000000-0000-0000-0000-000000000001"
            );

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString(
                    "92000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "93000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNANCE_DECISION_ID =
            UUID.fromString(
                    "94000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "95000000-0000-0000-0000-000000000001"
            );

    private static final Instant REQUESTED_AT =
            Instant.parse(
                    "2026-09-02T03:00:00Z"
            );

    private static final Instant EXPIRES_AT =
            Instant.parse(
                    "2026-09-02T03:15:00Z"
            );

    private static final Instant EXPIRED_AT =
            Instant.parse(
                    "2026-09-02T03:15:01Z"
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

    private ApprovalRequestExpiryStore
            expiryStore;

    private DefaultApprovalRequestExpirer expirer;

    @BeforeEach
    void setUp() {
        repository =
                mock(
                        ApprovalRequestRepository.class
                );

        expiryStore =
                mock(
                        ApprovalRequestExpiryStore.class
                );

        expirer =
                new DefaultApprovalRequestExpirer(
                        repository,
                        expiryStore
                );
    }

    @Test
    void expiresEligiblePendingRequest() {
        ApprovalRequest expired =
                pending().expire(
                        EXPIRED_AT
                );

        when(
                expiryStore.expirePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
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
                        expired
                )
        );

        ApprovalRequest result =
                expirer.expire(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
                );

        assertThat(result)
                .isEqualTo(
                        expired
                );

        assertThat(result.isExpired())
                .isTrue();
    }

    @Test
    void exactExpiryRetryReturnsOriginalExpiredState() {
        Instant originalExpiredAt =
                EXPIRES_AT;

        ApprovalRequest expired =
                pending().expire(
                        originalExpiredAt
                );

        Instant retryAt =
                EXPIRED_AT.plusSeconds(
                        60
                );

        when(
                expiryStore.expirePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        retryAt
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
                        expired
                )
        );

        ApprovalRequest result =
                expirer.expire(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        retryAt
                );

        assertThat(result)
                .isEqualTo(
                        expired
                );

        ApprovalState.Expired state =
                (ApprovalState.Expired)
                        result.state();

        assertThat(
                state.expiredAt()
        ).isEqualTo(
                originalExpiredAt
        );
    }

    @Test
    void expiryDoesNotOverwriteApprovedRequest() {
        ApprovalRequest approved =
                pending().approve(
                        ACTOR,
                        RATIONALE,
                        REQUESTED_AT.plusSeconds(
                                60
                        )
                );

        when(
                expiryStore.expirePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
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
                expirer.expire(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
                );

        assertThat(result)
                .isEqualTo(
                        approved
                );

        assertThat(result.isApproved())
                .isTrue();

        assertThat(
                result.isApprovedAndValidAt(
                        EXPIRED_AT
                )
        ).isFalse();
    }

    @Test
    void expiryDoesNotOverwriteRejectedRequest() {
        ApprovalRequest rejected =
                pending().reject(
                        ACTOR,
                        RATIONALE,
                        REQUESTED_AT.plusSeconds(
                                60
                        )
                );

        when(
                expiryStore.expirePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
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

        ApprovalRequest result =
                expirer.expire(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
                );

        assertThat(result)
                .isEqualTo(
                        rejected
                );

        assertThat(result.isRejected())
                .isTrue();
    }

    @Test
    void rejectsExpiryBeforeConfiguredBoundary() {
        Instant tooEarly =
                EXPIRES_AT.minusSeconds(
                        1
                );

        ApprovalRequest pending =
                pending();

        when(
                expiryStore.expirePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        tooEarly
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
                () -> expirer.expire(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        tooEarly
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "cannot expire before"
                );
    }

    @Test
    void unknownApprovalRequestFailsClosed() {
        when(
                expiryStore.expirePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
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
                Optional.empty()
        );

        assertThatThrownBy(
                () -> expirer.expire(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
                )
        )
                .isInstanceOf(
                        ApprovalRequestNotFoundException.class
                );
    }

    @Test
    void eligibleRequestRemainingPendingFailsClosed() {
        ApprovalRequest pending =
                pending();

        when(
                expiryStore.expirePending(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
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
                () -> expirer.expire(
                        ORGANIZATION_ID,
                        APPROVAL_REQUEST_ID,
                        EXPIRED_AT
                )
        )
                .isInstanceOf(
                        ApprovalRequestIntegrityException.class
                )
                .hasMessageContaining(
                        "remained pending"
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