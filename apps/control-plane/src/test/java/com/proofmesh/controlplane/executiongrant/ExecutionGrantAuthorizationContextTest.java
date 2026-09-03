package com.proofmesh.controlplane.executiongrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalState;
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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class ExecutionGrantAuthorizationContextTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000001");

    private static final UUID AGENT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000001");

    private static final UUID ACTION_ID =
            UUID.fromString("73000000-0000-0000-0000-000000000001");

    private static final UUID DECISION_ID =
            UUID.fromString("74000000-0000-0000-0000-000000000001");

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString("75000000-0000-0000-0000-000000000001");

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(UUID.fromString("76000000-0000-0000-0000-000000000001"));

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(UUID.fromString("77000000-0000-0000-0000-000000000001"));

    private static final ToolName TOOL_NAME =
            new ToolName("stripe");

    private static final OperationName OPERATION_NAME =
            new OperationName("refund_payment");

    private static final RequestPayloadHash REQUEST_PAYLOAD_HASH =
            new RequestPayloadHash("a".repeat(64));

    private static final Instant CREATED_AT =
            Instant.parse("2026-09-02T10:00:00Z");

    private static final Instant EXPIRES_AT =
            CREATED_AT.plus(Duration.ofMinutes(15));

    @Test
    void preservesValidValuesWithPresentApproval() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision();
        ApprovalRequest approval = approvalRequest();

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        action,
                        decision,
                        Optional.of(approval)
                );

        assertThat(context.governedAction())
                .isSameAs(action);
        assertThat(context.governanceDecision())
                .isSameAs(decision);
        assertThat(context.approvalRequest())
                .containsSame(approval);
    }

    @Test
    void preservesValidValuesWithEmptyApproval() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision();

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        action,
                        decision,
                        Optional.empty()
                );

        assertThat(context.governedAction())
                .isSameAs(action);
        assertThat(context.governanceDecision())
                .isSameAs(decision);
        assertThat(context.approvalRequest())
                .isEmpty();
    }

    @Test
    void rejectsNullGovernedAction() {
        GovernanceDecision decision = governanceDecision();

        assertThatThrownBy(
                () -> new ExecutionGrantAuthorizationContext(
                        null,
                        decision,
                        Optional.empty()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("governedAction must not be null");
    }

    @Test
    void rejectsNullGovernanceDecision() {
        GovernedAction action = governedAction();

        assertThatThrownBy(
                () -> new ExecutionGrantAuthorizationContext(
                        action,
                        null,
                        Optional.empty()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("governanceDecision must not be null");
    }

    @Test
    void rejectsNullApprovalRequestOptional() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision();

        assertThatThrownBy(
                () -> new ExecutionGrantAuthorizationContext(
                        action,
                        decision,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("approvalRequest must not be null");
    }

    private static GovernedAction governedAction() {
        return new GovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey("context-test-key"),
                TOOL_NAME,
                OPERATION_NAME,
                new CanonicalRequestPayload("{\"amount\":100}", REQUEST_PAYLOAD_HASH),
                CREATED_AT
        );
    }

    private static GovernanceDecision governanceDecision() {
        return new GovernanceDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                DecisionOutcome.ALLOW,
                new RiskScore(50),
                List.of(new DecisionReasonCode("LOW_RISK_OPERATION")),
                CREATED_AT
        );
    }

    private static ApprovalRequest approvalRequest() {
        return new ApprovalRequest(
                APPROVAL_REQUEST_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                DECISION_ID,
                AGENT_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                CREATED_AT,
                EXPIRES_AT,
                new ApprovalState.Approved(
                        new ApprovalActorId("operator-001"),
                        new ApprovalRationale("Approved"),
                        CREATED_AT.plusSeconds(30)
                )
        );
    }
}
