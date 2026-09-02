package com.proofmesh.controlplane.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestCreator;
import com.proofmesh.controlplane.approval.ApprovalRequestInsertResult;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class DefaultApprovalRequestMaterializerTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "a1000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "a2000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNANCE_DECISION_ID =
            UUID.fromString(
                    "a3000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "a4000000-0000-0000-0000-000000000001"
            );

    private static final Instant REQUESTED_AT =
            Instant.parse(
                    "2026-09-02T14:00:00Z"
            );

    private static final Instant EXPIRES_AT =
            Instant.parse(
                    "2026-09-02T14:15:00Z"
            );

    private ApprovalRequestCreator
            approvalRequestCreator;

    private ApprovalRequestRepository
            approvalRequestRepository;

    private DefaultApprovalRequestMaterializer
            materializer;

    private GovernedAction governedAction;

    private GovernanceDecision governanceDecision;

    @BeforeEach
    void setUp() {
        approvalRequestCreator =
                mock(
                        ApprovalRequestCreator.class
                );

        approvalRequestRepository =
                mock(
                        ApprovalRequestRepository.class
                );

        governedAction =
                mock(
                        GovernedAction.class
                );

        governanceDecision =
                mock(
                        GovernanceDecision.class
                );

        materializer =
                new DefaultApprovalRequestMaterializer(
                        approvalRequestCreator,
                        approvalRequestRepository
                );
    }

    @Test
    void returnsNewlyInsertedApprovalRequest() {
        ApprovalRequest candidate =
                pendingRequest(
                        UUID.randomUUID(),
                        REQUESTED_AT,
                        EXPIRES_AT
                );

        when(
                approvalRequestCreator.create(
                        any(UUID.class),
                        any(GovernedAction.class),
                        any(GovernanceDecision.class),
                        any(Instant.class),
                        any(Instant.class)
                )
        ).thenReturn(
                candidate
        );

        when(
                approvalRequestRepository
                        .insertIfAbsent(
                                candidate
                        )
        ).thenReturn(
                new ApprovalRequestInsertResult.Inserted(
                        candidate
                )
        );

        ApprovalRequest result =
                materializer.materialize(
                        governedAction,
                        governanceDecision,
                        REQUESTED_AT,
                        EXPIRES_AT
                );

        assertThat(result)
                .isEqualTo(
                        candidate
                );

        verify(
                approvalRequestRepository
        ).insertIfAbsent(
                candidate
        );
    }

    @Test
    void retryReturnsExistingAuthoritativeApprovalRequest() {
        ApprovalRequest candidate =
                pendingRequest(
                        UUID.randomUUID(),
                        REQUESTED_AT.plusSeconds(
                                300
                        ),
                        EXPIRES_AT.plusSeconds(
                                300
                        )
                );

        ApprovalRequest authoritative =
                pendingRequest(
                        UUID.randomUUID(),
                        REQUESTED_AT,
                        EXPIRES_AT
                );

        when(
                approvalRequestCreator.create(
                        any(UUID.class),
                        any(GovernedAction.class),
                        any(GovernanceDecision.class),
                        any(Instant.class),
                        any(Instant.class)
                )
        ).thenReturn(
                candidate
        );

        when(
                approvalRequestRepository
                        .insertIfAbsent(
                                candidate
                        )
        ).thenReturn(
                new ApprovalRequestInsertResult.Existing(
                        authoritative
                )
        );

        ApprovalRequest result =
                materializer.materialize(
                        governedAction,
                        governanceDecision,
                        REQUESTED_AT.plusSeconds(
                                300
                        ),
                        EXPIRES_AT.plusSeconds(
                                300
                        )
                );

        assertThat(result)
                .isEqualTo(
                        authoritative
                );

        assertThat(
                result.id()
        ).isNotEqualTo(
                candidate.id()
        );

        assertThat(
                result.requestedAt()
        ).isEqualTo(
                REQUESTED_AT
        );

        assertThat(
                result.expiresAt()
        ).isEqualTo(
                EXPIRES_AT
        );
    }

    @Test
    void retryReturnsExistingTerminalApprovalWithoutRecreatingIt() {
        ApprovalRequest candidate =
                pendingRequest(
                        UUID.randomUUID(),
                        REQUESTED_AT.plusSeconds(
                                60
                        ),
                        EXPIRES_AT.plusSeconds(
                                60
                        )
                );

        ApprovalRequest authoritative =
                pendingRequest(
                        UUID.randomUUID(),
                        REQUESTED_AT,
                        EXPIRES_AT
                )
                        .expire(
                                EXPIRES_AT
                        );

        when(
                approvalRequestCreator.create(
                        any(UUID.class),
                        any(GovernedAction.class),
                        any(GovernanceDecision.class),
                        any(Instant.class),
                        any(Instant.class)
                )
        ).thenReturn(
                candidate
        );

        when(
                approvalRequestRepository
                        .insertIfAbsent(
                                candidate
                        )
        ).thenReturn(
                new ApprovalRequestInsertResult.Existing(
                        authoritative
                )
        );

        ApprovalRequest result =
                materializer.materialize(
                        governedAction,
                        governanceDecision,
                        REQUESTED_AT.plusSeconds(
                                60
                        ),
                        EXPIRES_AT.plusSeconds(
                                60
                        )
                );

        assertThat(result)
                .isEqualTo(
                        authoritative
                );

        assertThat(
                result.isExpired()
        ).isTrue();
    }

    @Test
    void failsClosedWhenExistingRequestHasDifferentBinding() {
        ApprovalRequest candidate =
                pendingRequest(
                        UUID.randomUUID(),
                        REQUESTED_AT,
                        EXPIRES_AT
                );

        ApprovalRequest inconsistent =
                new ApprovalRequest(
                        UUID.randomUUID(),
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        GOVERNANCE_DECISION_ID,
                        UUID.randomUUID(),
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

        when(
                approvalRequestCreator.create(
                        any(UUID.class),
                        any(GovernedAction.class),
                        any(GovernanceDecision.class),
                        any(Instant.class),
                        any(Instant.class)
                )
        ).thenReturn(
                candidate
        );

        when(
                approvalRequestRepository
                        .insertIfAbsent(
                                candidate
                        )
        ).thenReturn(
                new ApprovalRequestInsertResult.Existing(
                        inconsistent
                )
        );

        assertThatThrownBy(
                () -> materializer.materialize(
                        governedAction,
                        governanceDecision,
                        REQUESTED_AT,
                        EXPIRES_AT
                )
        )
                .isInstanceOf(
                        ApprovalRequestIntegrityException.class
                )
                .hasMessageContaining(
                        "does not match"
                );
    }

    private ApprovalRequest pendingRequest(
            UUID approvalRequestId,
            Instant requestedAt,
            Instant expiresAt
    ) {
        return new ApprovalRequest(
                approvalRequestId,
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
                requestedAt,
                expiresAt,
                new ApprovalState.Pending()
        );
    }
}