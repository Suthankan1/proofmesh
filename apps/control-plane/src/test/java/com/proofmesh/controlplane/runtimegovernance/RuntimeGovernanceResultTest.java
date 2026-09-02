package com.proofmesh.controlplane.runtimegovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.RiskScore;
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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class RuntimeGovernanceResultTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "c1000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "c2000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "c3000000-0000-0000-0000-000000000001"
            );

    private static final UUID ASSESSMENT_ID =
            UUID.fromString(
                    "c4000000-0000-0000-0000-000000000001"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "c5000000-0000-0000-0000-000000000001"
            );

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString(
                    "c6000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "c7000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "c8000000-0000-0000-0000-000000000001"
                    )
            );

    private static final RiskScore RISK_SCORE =
            new RiskScore(
                    90
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T12:00:00Z"
            );

    private static final Instant APPROVAL_EXPIRES_AT =
            EVALUATED_AT.plus(
                    Duration.ofMinutes(
                            15
                    )
            );

    @Test
    void requireApprovalDecisionRequiresApprovalRequest() {
        assertThatThrownBy(
                () -> new RuntimeGovernanceResult.Governed(
                        policyBinding(),
                        riskAssessment(),
                        requireApprovalDecision()
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
    void nonApprovalDecisionRejectsApprovalRequest() {
        assertThatThrownBy(
                () -> new RuntimeGovernanceResult.Governed(
                        policyBinding(),
                        riskAssessment(),
                        denyDecision(),
                        Optional.of(
                                approvalRequest()
                        )
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
    void matchingRequireApprovalDecisionAcceptsApprovalRequest() {
        ApprovalRequest approvalRequest =
                approvalRequest();

        RuntimeGovernanceResult.Governed governed =
                new RuntimeGovernanceResult.Governed(
                        policyBinding(),
                        riskAssessment(),
                        requireApprovalDecision(),
                        Optional.of(
                                approvalRequest
                        )
                );

        assertThat(
                governed.approvalRequest()
        ).contains(
                approvalRequest
        );
    }

    @Test
    void denyDecisionWithoutApprovalRequestIsValid() {
        RuntimeGovernanceResult.Governed governed =
                new RuntimeGovernanceResult.Governed(
                        policyBinding(),
                        riskAssessment(),
                        denyDecision()
                );

        assertThat(
                governed.approvalRequest()
        ).isEmpty();
    }

    private AgentPolicyBinding policyBinding() {
        AgentPolicyBinding binding =
                mock(
                        AgentPolicyBinding.class
                );

        when(
                binding.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                binding.agentId()
        ).thenReturn(
                AGENT_ID
        );

        when(
                binding.policyVersionId()
        ).thenReturn(
                POLICY_VERSION_ID
        );

        return binding;
    }

    private RiskAssessment riskAssessment() {
        return new RiskAssessment(
                ASSESSMENT_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                RISK_SCORE,
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

    private GovernanceDecision requireApprovalDecision() {
        return new GovernanceDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                DecisionOutcome.REQUIRE_APPROVAL,
                RISK_SCORE,
                List.of(
                        new DecisionReasonCode(
                                "HIGH_RISK_REFUND"
                        )
                ),
                EVALUATED_AT
        );
    }

    private GovernanceDecision denyDecision() {
        return new GovernanceDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                null,
                DecisionOutcome.DENY,
                RISK_SCORE,
                List.of(
                        new DecisionReasonCode(
                                "NO_APPLICABLE_POLICY_RULE"
                        )
                ),
                EVALUATED_AT
        );
    }

    private ApprovalRequest approvalRequest() {
        return new ApprovalRequest(
                APPROVAL_REQUEST_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                DECISION_ID,
                AGENT_ID,
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                new RequestPayloadHash(
                        "a".repeat(
                                64
                        )
                ),
                EVALUATED_AT,
                APPROVAL_EXPIRES_AT,
                new ApprovalState.Pending()
        );
    }
}