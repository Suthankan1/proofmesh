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
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskLogicVersion;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceResult;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class ExecutionGrantEligibilityEvaluatorTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "91000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "92000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "93000000-0000-0000-0000-000000000001"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "94000000-0000-0000-0000-000000000001"
            );

    private static final UUID POLICY_BINDING_ID =
            UUID.fromString(
                    "95000000-0000-0000-0000-000000000001"
            );

    private static final UUID RISK_ASSESSMENT_ID =
            UUID.fromString(
                    "96000000-0000-0000-0000-000000000001"
            );

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString(
                    "97000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "98000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "99000000-0000-0000-0000-000000000001"
                    )
            );

    private static final ToolName TOOL_NAME =
            new ToolName(
                    "stripe"
            );

    private static final OperationName OPERATION_NAME =
            new OperationName(
                    "refund_payment"
            );

    private static final RequestPayloadHash REQUEST_PAYLOAD_HASH =
            new RequestPayloadHash(
                    "a".repeat(
                            64
                    )
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-02T10:00:00Z"
            );

    private static final Instant APPROVED_AT =
            EVALUATED_AT.plus(
                    Duration.ofMinutes(
                            1
                    )
            );

    private static final Instant EXPIRES_AT =
            EVALUATED_AT.plus(
                    Duration.ofMinutes(
                            15
                    )
            );

    private final ExecutionGrantEligibilityEvaluator evaluator =
            new ExecutionGrantEligibilityEvaluator();

    @Test
    void exactAllowWithMatchingActionResultProvenanceIsEligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantEligibility.Eligible.class
                );
    }

    @Test
    void allowWithActionOrganizationMismatchIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedActionWithOrganizationId(
                                UUID.randomUUID()
                        ),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .ACTION_PROVENANCE_MISMATCH
        );
    }

    @Test
    void allowWithActionIdentityMismatchIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedActionWithId(
                                UUID.randomUUID()
                        ),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .ACTION_PROVENANCE_MISMATCH
        );
    }

    @Test
    void allowWithActionAgentBindingMismatchIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedActionWithAgentId(
                                UUID.randomUUID()
                        ),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .ACTION_PROVENANCE_MISMATCH
        );
    }

    @Test
    void denyWithMatchingProvenanceIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                DecisionOutcome.DENY
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .DECISION_DENIED
        );
    }

    @Test
    void requireApprovalWithCurrentlyValidApprovedApprovalIsEligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVED_AT
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantEligibility.Eligible.class
                );
    }

    @Test
    void pendingApprovalIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                pendingApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .APPROVAL_NOT_CURRENTLY_VALID
        );
    }

    @Test
    void rejectedApprovalIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                rejectedApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .APPROVAL_NOT_CURRENTLY_VALID
        );
    }

    @Test
    void expiredApprovalStateIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                expiredApproval()
                        ),
                        EXPIRES_AT.plusSeconds(
                                1
                        )
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .APPROVAL_NOT_CURRENTLY_VALID
        );
    }

    @Test
    void historicalApprovedApprovalAtExpiresAtIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                approvedApproval()
                        ),
                        EXPIRES_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .APPROVAL_NOT_CURRENTLY_VALID
        );
    }

    @Test
    void historicalApprovedApprovalAfterExpiresAtIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                approvedApproval()
                        ),
                        EXPIRES_AT.plusNanos(
                                1
                        )
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .APPROVAL_NOT_CURRENTLY_VALID
        );
    }

    @Test
    void approvedApprovalWithToolMismatchIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedActionWithToolName(
                                new ToolName(
                                        "github"
                                )
                        ),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .APPROVAL_PROVENANCE_MISMATCH
        );
    }

    @Test
    void approvedApprovalWithOperationMismatchIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedActionWithOperationName(
                                new OperationName(
                                        "create_issue"
                                )
                        ),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .APPROVAL_PROVENANCE_MISMATCH
        );
    }

    @Test
    void approvedApprovalWithPayloadHashMismatchIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedActionWithPayloadHash(
                                new RequestPayloadHash(
                                        "b".repeat(
                                                64
                                        )
                                )
                        ),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .APPROVAL_PROVENANCE_MISMATCH
        );
    }

    @Test
    void failedClosedRuntimeResultIsIneligible() {
        ExecutionGrantEligibility result =
                evaluator.evaluate(
                        governedAction(),
                        new RuntimeGovernanceResult.FailedClosed(
                                ORGANIZATION_ID,
                                AGENT_ID,
                                ACTION_ID,
                                RuntimeGovernanceFailureReason
                                        .GOVERNANCE_DECISION_CONFLICT
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason
                        .FAILED_CLOSED
        );
    }

    @Test
    void rejectsNullGovernedAction() {
        assertThatThrownBy(
                () -> evaluator.evaluate(
                        null,
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "governedAction must not be null"
                );
    }

    @Test
    void rejectsNullRuntimeResult() {
        assertThatThrownBy(
                () -> evaluator.evaluate(
                        governedAction(),
                        null,
                        APPROVED_AT
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "runtimeResult must not be null"
                );
    }

    @Test
    void rejectsNullNow() {
        assertThatThrownBy(
                () -> evaluator.evaluate(
                        governedAction(),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "now must not be null"
                );
    }

    @Test
    void ineligibleRejectsNullReason() {
        assertThatThrownBy(
                () -> new ExecutionGrantEligibility.Ineligible(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "reason must not be null"
                );
    }

    private static void assertIneligible(
            ExecutionGrantEligibility result,
            ExecutionGrantEligibility.Reason reason
    ) {
        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantEligibility.Ineligible.class
                );

        ExecutionGrantEligibility.Ineligible ineligible =
                (ExecutionGrantEligibility.Ineligible)
                        result;

        assertThat(
                ineligible.reason()
        ).isEqualTo(
                reason
        );
    }

    private static RuntimeGovernanceResult.Governed governedResult(
            DecisionOutcome outcome
    ) {
        return new RuntimeGovernanceResult.Governed(
                policyBinding(),
                riskAssessment(),
                governanceDecision(
                        outcome
                )
        );
    }

    private static RuntimeGovernanceResult.Governed governedResult(
            ApprovalRequest approvalRequest
    ) {
        return new RuntimeGovernanceResult.Governed(
                policyBinding(),
                riskAssessment(),
                governanceDecision(
                        DecisionOutcome.REQUIRE_APPROVAL
                ),
                Optional.of(
                        approvalRequest
                )
        );
    }

    private static GovernedAction governedAction() {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                ACTION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithOrganizationId(
            UUID organizationId
    ) {
        return governedAction(
                organizationId,
                AGENT_ID,
                ACTION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithId(
            UUID actionId
    ) {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                actionId,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithAgentId(
            UUID agentId
    ) {
        return governedAction(
                ORGANIZATION_ID,
                agentId,
                ACTION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithToolName(
            ToolName toolName
    ) {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                ACTION_ID,
                toolName,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithOperationName(
            OperationName operationName
    ) {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                ACTION_ID,
                TOOL_NAME,
                operationName,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithPayloadHash(
            RequestPayloadHash requestPayloadHash
    ) {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                ACTION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                requestPayloadHash
        );
    }

    private static GovernedAction governedAction(
            UUID organizationId,
            UUID agentId,
            UUID actionId,
            ToolName toolName,
            OperationName operationName,
            RequestPayloadHash requestPayloadHash
    ) {
        return new GovernedAction(
                actionId,
                organizationId,
                agentId,
                new IdempotencyKey(
                        "eligibility-test-key"
                ),
                toolName,
                operationName,
                new CanonicalRequestPayload(
                        "{\"amount\":100}",
                        requestPayloadHash
                ),
                EVALUATED_AT
        );
    }

    private static AgentPolicyBinding policyBinding() {
        return new AgentPolicyBinding(
                POLICY_BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                EVALUATED_AT.minusSeconds(
                        60
                ),
                null,
                EVALUATED_AT.minusSeconds(
                        60
                )
        );
    }

    private static RiskAssessment riskAssessment() {
        return new RiskAssessment(
                RISK_ASSESSMENT_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                riskScore(),
                List.of(
                        new RiskSignal(
                                new RiskSignalCode(
                                        "HIGH_RISK_OPERATION"
                                ),
                                RiskSeverity.HIGH,
                                90,
                                "Operation carries deterministic high runtime risk."
                        )
                ),
                EVALUATED_AT
        );
    }

    private static GovernanceDecision governanceDecision(
            DecisionOutcome outcome
    ) {
        return new GovernanceDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                matchedPolicyRuleId(
                        outcome
                ),
                outcome,
                riskScore(),
                reasonCodes(
                        outcome
                ),
                EVALUATED_AT
        );
    }

    private static PolicyRuleId matchedPolicyRuleId(
            DecisionOutcome outcome
    ) {
        if (outcome == DecisionOutcome.DENY) {
            return null;
        }

        return POLICY_RULE_ID;
    }

    private static List<DecisionReasonCode> reasonCodes(
            DecisionOutcome outcome
    ) {
        return switch (outcome) {
            case ALLOW ->
                    List.of(
                            new DecisionReasonCode(
                                    "LOW_RISK_OPERATION"
                            )
                    );

            case DENY ->
                    List.of(
                            new DecisionReasonCode(
                                    "NO_APPLICABLE_POLICY_RULE"
                            )
                    );

            case REQUIRE_APPROVAL ->
                    List.of(
                            new DecisionReasonCode(
                                    "HIGH_RISK_REFUND"
                            )
                    );
        };
    }

    private static RiskScore riskScore() {
        return new RiskScore(
                90
        );
    }

    private static ApprovalRequest pendingApproval() {
        return approvalRequest(
                new ApprovalState.Pending()
        );
    }

    private static ApprovalRequest approvedApproval() {
        return pendingApproval()
                .approve(
                        new ApprovalActorId(
                                "operator-subject-001"
                        ),
                        new ApprovalRationale(
                                "Reviewed and authorized for exact runtime retry."
                        ),
                        APPROVED_AT
                );
    }

    private static ApprovalRequest rejectedApproval() {
        return pendingApproval()
                .reject(
                        new ApprovalActorId(
                                "operator-subject-001"
                        ),
                        new ApprovalRationale(
                                "Rejected after human review."
                        ),
                        APPROVED_AT
                );
    }

    private static ApprovalRequest expiredApproval() {
        return pendingApproval()
                .expire(
                        EXPIRES_AT
                );
    }

    private static ApprovalRequest approvalRequest(
            ApprovalState state
    ) {
        return new ApprovalRequest(
                APPROVAL_REQUEST_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                DECISION_ID,
                AGENT_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                EVALUATED_AT,
                EXPIRES_AT,
                state
        );
    }
}
