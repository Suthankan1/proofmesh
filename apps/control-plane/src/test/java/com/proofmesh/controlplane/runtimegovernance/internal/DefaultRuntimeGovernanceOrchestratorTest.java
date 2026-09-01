package com.proofmesh.controlplane.runtimegovernance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
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
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceOrchestrator;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultRuntimeGovernanceOrchestratorTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "b1000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "b2000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "b3000000-0000-0000-0000-000000000001"
            );

    private static final UUID ASSESSMENT_ID =
            UUID.fromString(
                    "b4000000-0000-0000-0000-000000000001"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "b5000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "b6000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "b7000000-0000-0000-0000-000000000001"
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

    private RuntimeGovernanceContextResolver
            contextResolver;

    private RuntimeRiskAssessmentResolver
            riskAssessmentResolver;

    private RuntimePolicyEvaluationResolver
            policyEvaluationResolver;

    private RuntimeGovernanceDecisionResolver
            governanceDecisionResolver;

    private RuntimeGovernanceOrchestrator
            orchestrator;

    @BeforeEach
    void setUp() {
        contextResolver =
                mock(
                        RuntimeGovernanceContextResolver.class
                );

        riskAssessmentResolver =
                mock(
                        RuntimeRiskAssessmentResolver.class
                );

        policyEvaluationResolver =
                mock(
                        RuntimePolicyEvaluationResolver.class
                );

        governanceDecisionResolver =
                mock(
                        RuntimeGovernanceDecisionResolver.class
                );

        orchestrator =
                new DefaultRuntimeGovernanceOrchestrator(
                        contextResolver,
                        riskAssessmentResolver,
                        policyEvaluationResolver,
                        governanceDecisionResolver
                );
    }

    @Test
    void governsActionThroughAuthoritativeRuntimePipeline() {
        RuntimeGovernanceRequest request =
                request();

        RuntimeGovernanceContext context =
                context();

        RiskAssessment riskAssessment =
                riskAssessment();

        PolicyEvaluationResult evaluationResult =
                matchedEvaluation();

        GovernanceDecision decision =
                matchedDecision();

        RuntimeGovernanceContextResolution.Ready contextReady =
                new RuntimeGovernanceContextResolution.Ready(
                        context
                );

        RuntimeRiskAssessmentResolution.Ready riskReady =
                new RuntimeRiskAssessmentResolution.Ready(
                        riskAssessment
                );

        RuntimeGovernanceDecisionResolution.Ready decisionReady =
                new RuntimeGovernanceDecisionResolution.Ready(
                        decision
                );

        when(
                contextResolver.resolve(
                        request
                )
        ).thenReturn(
                contextReady
        );

        when(
                riskAssessmentResolver.resolve(
                        request
                )
        ).thenReturn(
                riskReady
        );

        when(
                policyEvaluationResolver.evaluate(
                        context,
                        request,
                        riskAssessment
                )
        ).thenReturn(
                evaluationResult
        );

        when(
                governanceDecisionResolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                )
        ).thenReturn(
                decisionReady
        );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernanceResult.Governed.class
                );

        RuntimeGovernanceResult.Governed governed =
                (RuntimeGovernanceResult.Governed)
                        result;

        assertThat(
                governed.policyBinding()
        ).isSameAs(
                context.policyBinding()
        );

        assertThat(
                governed.riskAssessment()
        ).isSameAs(
                riskAssessment
        );

        assertThat(
                governed.decision()
        ).isSameAs(
                decision
        );

        InOrder order =
                inOrder(
                        contextResolver,
                        riskAssessmentResolver,
                        policyEvaluationResolver,
                        governanceDecisionResolver
                );

        order.verify(
                contextResolver
        ).resolve(
                request
        );

        order.verify(
                riskAssessmentResolver
        ).resolve(
                request
        );

        order.verify(
                policyEvaluationResolver
        ).evaluate(
                context,
                request,
                riskAssessment
        );

        order.verify(
                governanceDecisionResolver
        ).resolve(
                context,
                request,
                riskAssessment,
                evaluationResult
        );
    }

    @Test
    void contextFailureShortCircuitsEntireRemainingPipeline() {
        RuntimeGovernanceRequest request =
                request();

        RuntimeGovernanceContextResolution.Failed failure =
                new RuntimeGovernanceContextResolution.Failed(
                        RuntimeGovernanceFailureReason
                                .AGENT_NOT_ACTIVE
                );

        when(
                contextResolver.resolve(
                        request
                )
        ).thenReturn(
                failure
        );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request
                );

        assertFailedClosed(
                result,
                RuntimeGovernanceFailureReason
                        .AGENT_NOT_ACTIVE
        );

        verifyNoInteractions(
                riskAssessmentResolver,
                policyEvaluationResolver,
                governanceDecisionResolver
        );
    }

    @Test
    void riskFailureStopsBeforePolicyEvaluation() {
        RuntimeGovernanceRequest request =
                request();

        RuntimeGovernanceContext context =
                context();

        RuntimeGovernanceContextResolution.Ready contextReady =
                new RuntimeGovernanceContextResolution.Ready(
                        context
                );

        RuntimeRiskAssessmentResolution.Failed riskFailure =
                new RuntimeRiskAssessmentResolution.Failed(
                        RuntimeGovernanceFailureReason
                                .RISK_ASSESSMENT_CONFLICT
                );

        when(
                contextResolver.resolve(
                        request
                )
        ).thenReturn(
                contextReady
        );

        when(
                riskAssessmentResolver.resolve(
                        request
                )
        ).thenReturn(
                riskFailure
        );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request
                );

        assertFailedClosed(
                result,
                RuntimeGovernanceFailureReason
                        .RISK_ASSESSMENT_CONFLICT
        );

        verifyNoInteractions(
                policyEvaluationResolver,
                governanceDecisionResolver
        );
    }

    @Test
    void decisionConflictFailsClosedAfterEvaluation() {
        RuntimeGovernanceRequest request =
                request();

        RuntimeGovernanceContext context =
                context();

        RiskAssessment riskAssessment =
                riskAssessment();

        PolicyEvaluationResult evaluationResult =
                matchedEvaluation();

        RuntimeGovernanceContextResolution.Ready contextReady =
                new RuntimeGovernanceContextResolution.Ready(
                        context
                );

        RuntimeRiskAssessmentResolution.Ready riskReady =
                new RuntimeRiskAssessmentResolution.Ready(
                        riskAssessment
                );

        RuntimeGovernanceDecisionResolution.Failed decisionFailure =
                new RuntimeGovernanceDecisionResolution.Failed(
                        RuntimeGovernanceFailureReason
                                .GOVERNANCE_DECISION_CONFLICT
                );

        when(
                contextResolver.resolve(
                        request
                )
        ).thenReturn(
                contextReady
        );

        when(
                riskAssessmentResolver.resolve(
                        request
                )
        ).thenReturn(
                riskReady
        );

        when(
                policyEvaluationResolver.evaluate(
                        context,
                        request,
                        riskAssessment
                )
        ).thenReturn(
                evaluationResult
        );

        when(
                governanceDecisionResolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                )
        ).thenReturn(
                decisionFailure
        );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request
                );

        assertFailedClosed(
                result,
                RuntimeGovernanceFailureReason
                        .GOVERNANCE_DECISION_CONFLICT
        );
    }

    @Test
    void policyDenyIsGovernedDecisionNotOrchestrationFailure() {
        RuntimeGovernanceRequest request =
                request();

        RuntimeGovernanceContext context =
                context();

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

        GovernanceDecision decision =
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

        RuntimeGovernanceContextResolution.Ready contextReady =
                new RuntimeGovernanceContextResolution.Ready(
                        context
                );

        RuntimeRiskAssessmentResolution.Ready riskReady =
                new RuntimeRiskAssessmentResolution.Ready(
                        riskAssessment
                );

        RuntimeGovernanceDecisionResolution.Ready decisionReady =
                new RuntimeGovernanceDecisionResolution.Ready(
                        decision
                );

        when(
                contextResolver.resolve(
                        request
                )
        ).thenReturn(
                contextReady
        );

        when(
                riskAssessmentResolver.resolve(
                        request
                )
        ).thenReturn(
                riskReady
        );

        when(
                policyEvaluationResolver.evaluate(
                        context,
                        request,
                        riskAssessment
                )
        ).thenReturn(
                evaluationResult
        );

        when(
                governanceDecisionResolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                )
        ).thenReturn(
                decisionReady
        );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernanceResult.Governed.class
                );

        RuntimeGovernanceResult.Governed governed =
                (RuntimeGovernanceResult.Governed)
                        result;

        assertThat(
                governed.decision().deniesExecution()
        ).isTrue();
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(
                () -> orchestrator.govern(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "request"
                );

        verifyNoInteractions(
                contextResolver,
                riskAssessmentResolver,
                policyEvaluationResolver,
                governanceDecisionResolver
        );
    }

    private void assertFailedClosed(
            RuntimeGovernanceResult result,
            RuntimeGovernanceFailureReason reason
    ) {
        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernanceResult.FailedClosed.class
                );

        RuntimeGovernanceResult.FailedClosed failed =
                (RuntimeGovernanceResult.FailedClosed)
                        result;

        assertThat(
                failed.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                failed.agentId()
        ).isEqualTo(
                AGENT_ID
        );

        assertThat(
                failed.governedActionId()
        ).isEqualTo(
                ACTION_ID
        );

        assertThat(
                failed.reason()
        ).isEqualTo(
                reason
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

    private GovernanceDecision matchedDecision() {
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
}