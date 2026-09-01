package com.proofmesh.controlplane.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestCreator;
import com.proofmesh.controlplane.approval.ApprovalStatus;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.approval.ApprovalStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultApprovalRequestCreatorTest {

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString(
                    "a1000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "a2000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "a2000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "a3000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "a4000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ACTION_ID =
            UUID.fromString(
                    "a4000000-0000-0000-0000-000000000002"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "a5000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "a6000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "a7000000-0000-0000-0000-000000000001"
                    )
            );

    private static final RequestPayloadHash PAYLOAD_HASH =
            new RequestPayloadHash(
                    "a".repeat(64)
            );

    private static final Instant ACTION_CREATED_AT =
            Instant.parse(
                    "2026-09-01T10:00:00Z"
            );

    private static final Instant DECIDED_AT =
            Instant.parse(
                    "2026-09-01T10:00:01Z"
            );

    private static final Instant REQUESTED_AT =
            Instant.parse(
                    "2026-09-01T10:00:02Z"
            );

    private static final Instant EXPIRES_AT =
            Instant.parse(
                    "2026-09-01T10:15:02Z"
            );

    private ApprovalRequestCreator creator;

    @BeforeEach
    void setUp() {
        creator =
                new DefaultApprovalRequestCreator();
    }

    @Test
    void createsApprovalRequestBoundToExactActionAndDecision() {
        GovernedAction action =
                governedAction();

        GovernanceDecision decision =
                approvalDecision(
                        ORGANIZATION_ID,
                        ACTION_ID
                );

        ApprovalRequest approvalRequest =
                creator.create(
                        APPROVAL_REQUEST_ID,
                        action,
                        decision,
                        REQUESTED_AT,
                        EXPIRES_AT
                );

        assertThat(
                approvalRequest.id()
        ).isEqualTo(
                APPROVAL_REQUEST_ID
        );

        assertThat(
                approvalRequest.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                approvalRequest.governedActionId()
        ).isEqualTo(
                ACTION_ID
        );

        assertThat(
                approvalRequest.governanceDecisionId()
        ).isEqualTo(
                DECISION_ID
        );

        assertThat(
                approvalRequest.agentId()
        ).isEqualTo(
                AGENT_ID
        );

        assertThat(
                approvalRequest.toolName()
        ).isEqualTo(
                new ToolName(
                        "stripe"
                )
        );

        assertThat(
                approvalRequest.operationName()
        ).isEqualTo(
                new OperationName(
                        "refund_payment"
                )
        );

        assertThat(
                approvalRequest.requestPayloadHash()
        ).isEqualTo(
                PAYLOAD_HASH
        );

        assertThat(
                approvalRequest.requestedAt()
        ).isEqualTo(
                REQUESTED_AT
        );

        assertThat(
                approvalRequest.expiresAt()
        ).isEqualTo(
                EXPIRES_AT
        );

        assertThat(
                approvalRequest.status()
        ).isEqualTo(
                ApprovalStatus.PENDING
        );
    }

    @Test
    void rejectsAllowDecision() {
        GovernanceDecision decision =
                decision(
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DecisionOutcome.ALLOW
                );

        assertThatThrownBy(
                () -> creator.create(
                        APPROVAL_REQUEST_ID,
                        governedAction(),
                        decision,
                        REQUESTED_AT,
                        EXPIRES_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "REQUIRE_APPROVAL"
                );
    }

    @Test
    void rejectsDenyDecision() {
        GovernanceDecision decision =
                decision(
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DecisionOutcome.DENY
                );

        assertThatThrownBy(
                () -> creator.create(
                        APPROVAL_REQUEST_ID,
                        governedAction(),
                        decision,
                        REQUESTED_AT,
                        EXPIRES_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "REQUIRE_APPROVAL"
                );
    }

    @Test
    void rejectsDecisionFromDifferentOrganization() {
        GovernanceDecision decision =
                approvalDecision(
                        OTHER_ORGANIZATION_ID,
                        ACTION_ID
                );

        assertThatThrownBy(
                () -> creator.create(
                        APPROVAL_REQUEST_ID,
                        governedAction(),
                        decision,
                        REQUESTED_AT,
                        EXPIRES_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "same organization"
                );
    }

    @Test
    void rejectsDecisionForDifferentGovernedAction() {
        GovernanceDecision decision =
                approvalDecision(
                        ORGANIZATION_ID,
                        OTHER_ACTION_ID
                );

        assertThatThrownBy(
                () -> creator.create(
                        APPROVAL_REQUEST_ID,
                        governedAction(),
                        decision,
                        REQUESTED_AT,
                        EXPIRES_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "governed action being approved"
                );
    }

    @Test
    void rejectsApprovalRequestThatPredatesDecision() {
        Instant beforeDecision =
                DECIDED_AT.minusSeconds(
                        1
                );

        assertThatThrownBy(
                () -> creator.create(
                        APPROVAL_REQUEST_ID,
                        governedAction(),
                        approvalDecision(
                                ORGANIZATION_ID,
                                ACTION_ID
                        ),
                        beforeDecision,
                        EXPIRES_AT
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
    void rejectsExpirationThatIsNotAfterRequestTime() {
        assertThatThrownBy(
                () -> creator.create(
                        APPROVAL_REQUEST_ID,
                        governedAction(),
                        approvalDecision(
                                ORGANIZATION_ID,
                                ACTION_ID
                        ),
                        REQUESTED_AT,
                        REQUESTED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "expiresAt must be after requestedAt"
                );
    }

    @Test
    void reportsExpirationAtBoundary() {
        ApprovalRequest approvalRequest =
                creator.create(
                        APPROVAL_REQUEST_ID,
                        governedAction(),
                        approvalDecision(
                                ORGANIZATION_ID,
                                ACTION_ID
                        ),
                        REQUESTED_AT,
                        EXPIRES_AT
                );

        assertThat(
                approvalRequest.isExpiredAt(
                        EXPIRES_AT.minusNanos(
                                1
                        )
                )
        ).isFalse();

        assertThat(
                approvalRequest.isExpiredAt(
                        EXPIRES_AT
                )
        ).isTrue();
    }

    private GovernedAction governedAction() {
        return new GovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey(
                        "approval-request-001"
                ),
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                new CanonicalRequestPayload(
                        "{\"amount\":5000,\"paymentId\":\"pay_123\"}",
                        PAYLOAD_HASH
                ),
                ACTION_CREATED_AT
        );
    }

    private GovernanceDecision approvalDecision(
            UUID organizationId,
            UUID governedActionId
    ) {
        return decision(
                organizationId,
                governedActionId,
                DecisionOutcome.REQUIRE_APPROVAL
        );
    }

    private GovernanceDecision decision(
            UUID organizationId,
            UUID governedActionId,
            DecisionOutcome outcome
    ) {
        return new GovernanceDecision(
                DECISION_ID,
                organizationId,
                governedActionId,
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                outcome,
                new RiskScore(
                        90
                ),
                List.of(
                        new DecisionReasonCode(
                                "HIGH_RISK_REFUND"
                        )
                ),
                DECIDED_AT
        );
    }
}