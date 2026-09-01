package com.proofmesh.controlplane.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class ApprovalRequestLifecycleTest {

    private static final Instant REQUESTED_AT =
            Instant.parse(
                    "2026-09-01T12:00:00Z"
            );

    private static final Instant EXPIRES_AT =
            Instant.parse(
                    "2026-09-01T12:15:00Z"
            );

    private static final Instant DECIDED_AT =
            Instant.parse(
                    "2026-09-01T12:05:00Z"
            );

    private static final ApprovalActorId ACTOR_ID =
            new ApprovalActorId(
                    "operator-subject-001"
            );

    private static final ApprovalRationale RATIONALE =
            new ApprovalRationale(
                    "Reviewed the requested refund and approved the operation."
            );

    @Test
    void startsPending() {
        ApprovalRequest request =
                pendingRequest();

        assertThat(
                request.status()
        ).isEqualTo(
                ApprovalStatus.PENDING
        );

        assertThat(
                request.isPending()
        ).isTrue();

        assertThat(
                request.isApprovedAndValidAt(
                        DECIDED_AT
                )
        ).isFalse();
    }

    @Test
    void approvesPendingRequestWithHumanResolution() {
        ApprovalRequest original =
                pendingRequest();

        ApprovalRequest approved =
                original.approve(
                        ACTOR_ID,
                        RATIONALE,
                        DECIDED_AT
                );

        assertThat(
                original.status()
        ).isEqualTo(
                ApprovalStatus.PENDING
        );

        assertThat(
                approved.status()
        ).isEqualTo(
                ApprovalStatus.APPROVED
        );

        assertThat(
                approved.state()
        ).isInstanceOf(
                ApprovalState.Approved.class
        );

        ApprovalState.Approved state =
                (ApprovalState.Approved)
                        approved.state();

        assertThat(
                state.actorId()
        ).isEqualTo(
                ACTOR_ID
        );

        assertThat(
                state.rationale()
        ).isEqualTo(
                RATIONALE
        );

        assertThat(
                state.decidedAt()
        ).isEqualTo(
                DECIDED_AT
        );

        assertThat(
                approved.isApprovedAndValidAt(
                        DECIDED_AT
                )
        ).isTrue();
    }

    @Test
    void rejectsPendingRequestWithHumanResolution() {
        ApprovalRequest rejected =
                pendingRequest()
                        .reject(
                                ACTOR_ID,
                                new ApprovalRationale(
                                        "Refund context did not justify the requested operation."
                                ),
                                DECIDED_AT
                        );

        assertThat(
                rejected.status()
        ).isEqualTo(
                ApprovalStatus.REJECTED
        );

        assertThat(
                rejected.state()
        ).isInstanceOf(
                ApprovalState.Rejected.class
        );

        assertThat(
                rejected.isApprovedAndValidAt(
                        DECIDED_AT
                )
        ).isFalse();
    }

    @Test
    void rejectsApprovalAtExpirationBoundary() {
        assertThatThrownBy(
                () -> pendingRequest()
                        .approve(
                                ACTOR_ID,
                                RATIONALE,
                                EXPIRES_AT
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "expired"
                );
    }

    @Test
    void rejectsHumanDecisionThatPredatesRequest() {
        assertThatThrownBy(
                () -> pendingRequest()
                        .approve(
                                ACTOR_ID,
                                RATIONALE,
                                REQUESTED_AT.minusSeconds(
                                        1
                                )
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "cannot predate"
                );
    }

    @Test
    void expiresPendingRequestAtExpirationBoundary() {
        ApprovalRequest expired =
                pendingRequest()
                        .expire(
                                EXPIRES_AT
                        );

        assertThat(
                expired.status()
        ).isEqualTo(
                ApprovalStatus.EXPIRED
        );

        assertThat(
                expired.state()
        ).isInstanceOf(
                ApprovalState.Expired.class
        );
    }

    @Test
    void rejectsExpirationBeforeExpirationBoundary() {
        assertThatThrownBy(
                () -> pendingRequest()
                        .expire(
                                EXPIRES_AT.minusNanos(
                                        1
                                )
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
    void rejectsSecondTransitionAfterApproval() {
        ApprovalRequest approved =
                pendingRequest()
                        .approve(
                                ACTOR_ID,
                                RATIONALE,
                                DECIDED_AT
                        );

        assertThatThrownBy(
                () -> approved.reject(
                        ACTOR_ID,
                        new ApprovalRationale(
                                "Attempted second resolution."
                        ),
                        DECIDED_AT.plusSeconds(
                                1
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "not pending"
                );
    }

    @Test
    void approvedDecisionRemainsApprovedAfterAuthorizationExpires() {
        ApprovalRequest approved =
                pendingRequest()
                        .approve(
                                ACTOR_ID,
                                RATIONALE,
                                DECIDED_AT
                        );

        assertThat(
                approved.isApprovedAndValidAt(
                        EXPIRES_AT.minusNanos(
                                1
                        )
                )
        ).isTrue();

        assertThat(
                approved.isApprovedAndValidAt(
                        EXPIRES_AT
                )
        ).isFalse();

        assertThat(
                approved.status()
        ).isEqualTo(
                ApprovalStatus.APPROVED
        );
    }

    @Test
    void rejectsBlankActorId() {
        assertThatThrownBy(
                () -> new ApprovalActorId(
                        "   "
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "blank"
                );
    }

    @Test
    void rejectsBlankRationale() {
        assertThatThrownBy(
                () -> new ApprovalRationale(
                        "   "
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "blank"
                );
    }

    private ApprovalRequest pendingRequest() {
        return new ApprovalRequest(
                UUID.fromString(
                        "d1000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "d2000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "d3000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "d4000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "d5000000-0000-0000-0000-000000000001"
                ),
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