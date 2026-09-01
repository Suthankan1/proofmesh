package com.proofmesh.controlplane.runtimegovernance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecorder;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecordingConflictException;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskLogicVersion;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultRuntimeGovernanceDecisionResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "a1000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "a2000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "a3000000-0000-0000-0000-000000000001"
            );

    private static final UUID ASSESSMENT_ID =
            UUID.fromString(
                    "a4000000-0000-0000-0000-000000000001"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "a5000000-0000-0000-0000-000000000001"
            );

    private static final UUID AUTHORITATIVE_DECISION_ID =
            UUID.fromString(
                    "a5000000-0000-0000-0000-000000000002"
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

    private static final RiskScore RISK_SCORE =
            new RiskScore(
                    90
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T11:00:00Z"
            );

    private GovernanceDecisionRecorder
            governanceDecisionRecorder;

    private RuntimeGovernanceDecisionResolver
            resolver;

    @BeforeEach
    void setUp() {
        governanceDecisionRecorder =
                mock(
                        GovernanceDecisionRecorder.class
                );

        resolver =
                new DefaultRuntimeGovernanceDecisionResolver(
                        governanceDecisionRecorder
                );
    }

    @Test
    void recordsAndReturnsAuthoritativeDecision() {
        RuntimeGovernanceContext context =
                context();

        RuntimeGovernanceRequest request =
                request();

        RiskAssessment riskAssessment =
                riskAssessment();

        PolicyEvaluationResult evaluationResult =
                matchedEvaluation();

        GovernanceDecision authoritative =
                matchedDecision(
                        AUTHORITATIVE_DECISION_ID,
                        EVALUATED_AT.minusSeconds(
                                5
                        )
                );

        when(
                governanceDecisionRecorder
                        .recordAuthoritativeDecision(
                                DECISION_ID,
                                ORGANIZATION_ID,
                                ACTION_ID,
                                evaluationResult,
                                EVALUATED_AT
                        )
        ).thenReturn(
                authoritative
        );

        RuntimeGovernanceDecisionResolution result =
                resolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernanceDecisionResolution
                                .Ready.class
                );

        RuntimeGovernanceDecisionResolution.Ready ready =
                (RuntimeGovernanceDecisionResolution.Ready)
                        result;

        assertThat(
                ready.decision()
        ).isSameAs(
                authoritative
        );
    }

    @Test
    void recordsUsingExactRuntimeIdentityAndEvaluationTime() {
        RuntimeGovernanceContext context =
                context();

        RuntimeGovernanceRequest request =
                request();

        RiskAssessment riskAssessment =
                riskAssessment();

        PolicyEvaluationResult evaluationResult =
                matchedEvaluation();

        GovernanceDecision authoritative =
                matchedDecision(
                        DECISION_ID,
                        EVALUATED_AT
                );

        when(
                governanceDecisionRecorder
                        .recordAuthoritativeDecision(
                                DECISION_ID,
                                ORGANIZATION_ID,
                                ACTION_ID,
                                evaluationResult,
                                EVALUATED_AT
                        )
        ).thenReturn(
                authoritative
        );

        resolver.resolve(
                context,
                request,
                riskAssessment,
                evaluationResult
        );

        verify(
                governanceDecisionRecorder
        ).recordAuthoritativeDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                evaluationResult,
                EVALUATED_AT
        );
    }

    @Test
    void preservesDefaultDeniedEvaluation() {
        RuntimeGovernanceContext context =
                context();

        RuntimeGovernanceRequest request =
                request();

        RiskAssessment riskAssessment =
                riskAssessment();

        PolicyEvaluationResult.DefaultDenied evaluationResult =
                new PolicyEvaluationResult.DefaultDenied(
                        POLICY_VERSION_ID,
                        RISK_SCORE,
                        new DecisionReasonCode(
                                "NO_APPLICABLE_POLICY_RULE"
                        )
                );

        GovernanceDecision authoritative =
                new GovernanceDecision(
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

        when(
                governanceDecisionRecorder
                        .recordAuthoritativeDecision(
                                DECISION_ID,
                                ORGANIZATION_ID,
                                ACTION_ID,
                                evaluationResult,
                                EVALUATED_AT
                        )
        ).thenReturn(
                authoritative
        );

        RuntimeGovernanceDecisionResolution result =
                resolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                );

        RuntimeGovernanceDecisionResolution.Ready ready =
                (RuntimeGovernanceDecisionResolution.Ready)
                        result;

        assertThat(
                ready.decision().deniesExecution()
        ).isTrue();

        assertThat(
                ready.decision().matchedPolicyRuleId()
        ).isNull();
    }

    @Test
    void decisionRecordingConflictFailsClosed() {
        RuntimeGovernanceContext context =
                context();

        RuntimeGovernanceRequest request =
                request();

        RiskAssessment riskAssessment =
                riskAssessment();

        PolicyEvaluationResult evaluationResult =
                matchedEvaluation();

        when(
                governanceDecisionRecorder
                        .recordAuthoritativeDecision(
                                DECISION_ID,
                                ORGANIZATION_ID,
                                ACTION_ID,
                                evaluationResult,
                                EVALUATED_AT
                        )
        ).thenThrow(
                new GovernanceDecisionRecordingConflictException(
                        "different evaluation semantics"
                )
        );

        RuntimeGovernanceDecisionResolution result =
                resolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimeGovernanceDecisionResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .GOVERNANCE_DECISION_CONFLICT
                        )
                );
    }

    @Test
    void rejectsEvaluationWithDifferentRiskScore() {
        RuntimeGovernanceContext context =
                context();

        RuntimeGovernanceRequest request =
                request();

        RiskAssessment riskAssessment =
                riskAssessment();

        PolicyEvaluationResult evaluationResult =
                new PolicyEvaluationResult.Matched(
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(
                                95
                        ),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        )
                );

        assertThatThrownBy(
                () -> resolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "score"
                );

        verifyNoInteractions(
                governanceDecisionRecorder
        );
    }

    @Test
    void rejectsEvaluationForDifferentPolicyVersion() {
        RuntimeGovernanceContext context =
                context();

        RuntimeGovernanceRequest request =
                request();

        RiskAssessment riskAssessment =
                riskAssessment();

        PolicyEvaluationResult evaluationResult =
                new PolicyEvaluationResult.Matched(
                        new PolicyVersionId(
                                UUID.randomUUID()
                        ),
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        RISK_SCORE,
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        )
                );

        assertThatThrownBy(
                () -> resolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "policy version"
                );

        verifyNoInteractions(
                governanceDecisionRecorder
        );
    }

    @Test
    void rejectsRiskAssessmentForDifferentAction() {
        RuntimeGovernanceContext context =
                context();

        RuntimeGovernanceRequest request =
                request();

        RiskAssessment riskAssessment =
                riskAssessment(
                        UUID.randomUUID()
                );

        PolicyEvaluationResult evaluationResult =
                matchedEvaluation();

        assertThatThrownBy(
                () -> resolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "identity"
                );

        verifyNoInteractions(
                governanceDecisionRecorder
        );
    }

    @Test
    void rejectsNullEvaluationResult() {
        assertThatThrownBy(
                () -> resolver.resolve(
                        context(),
                        request(),
                        riskAssessment(),
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "evaluationResult"
                );

        verifyNoInteractions(
                governanceDecisionRecorder
        );
    }

    private RuntimeGovernanceContext context() {
        Agent agent =
                mock(
                        Agent.class
                );

        AgentPolicyBinding binding =
                mock(
                        AgentPolicyBinding.class
                );

        PolicyVersion policyVersion =
                mock(
                        PolicyVersion.class
                );

        when(
                agent.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                agent.id()
        ).thenReturn(
                AGENT_ID
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

        when(
                policyVersion.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                policyVersion.id()
        ).thenReturn(
                POLICY_VERSION_ID
        );

        return new RuntimeGovernanceContext(
                agent,
                binding,
                policyVersion
        );
    }

    private RuntimeGovernanceRequest request() {
        GovernedAction governedAction =
                mock(
                        GovernedAction.class
                );

        when(
                governedAction.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                governedAction.agentId()
        ).thenReturn(
                AGENT_ID
        );

        when(
                governedAction.id()
        ).thenReturn(
                ACTION_ID
        );

        return new RuntimeGovernanceRequest(
                ASSESSMENT_ID,
                DECISION_ID,
                governedAction,
                List.of(),
                EVALUATED_AT
        );
    }

    private RiskAssessment riskAssessment() {
        return riskAssessment(
                ACTION_ID
        );
    }

    private RiskAssessment riskAssessment(
            UUID governedActionId
    ) {
        return new RiskAssessment(
                ASSESSMENT_ID,
                ORGANIZATION_ID,
                governedActionId,
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

    private PolicyEvaluationResult matchedEvaluation() {
        return new PolicyEvaluationResult.Matched(
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                DecisionOutcome.REQUIRE_APPROVAL,
                RISK_SCORE,
                List.of(
                        new DecisionReasonCode(
                                "HIGH_RISK_REFUND"
                        )
                )
        );
    }

    private GovernanceDecision matchedDecision(
            UUID decisionId,
            Instant decidedAt
    ) {
        return new GovernanceDecision(
                decisionId,
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
                decidedAt
        );
    }
}