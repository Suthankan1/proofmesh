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
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.PolicyEvaluator;
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
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultRuntimePolicyEvaluationResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "f1000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "f2000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "f3000000-0000-0000-0000-000000000001"
            );

    private static final UUID ASSESSMENT_ID =
            UUID.fromString(
                    "f4000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "f5000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "f6000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T10:30:00Z"
            );

    private static final RiskScore RISK_SCORE =
            new RiskScore(
                    65
            );

    private PolicyEvaluator policyEvaluator;

    private RuntimePolicyEvaluationResolver resolver;

    @BeforeEach
    void setUp() {
        policyEvaluator =
                mock(
                        PolicyEvaluator.class
                );

        resolver =
                new DefaultRuntimePolicyEvaluationResolver(
                        policyEvaluator
                );
    }

    @Test
    void evaluatesExactPolicyActionAndAuthoritativeRiskScore() {
        RuntimeGovernanceRequest request =
                request(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        ACTION_ID
                );

        RuntimeGovernanceContext context =
                context();

        RiskAssessment riskAssessment =
                riskAssessment(
                        ORGANIZATION_ID,
                        ACTION_ID
                );

        PolicyEvaluationResult expected =
                matchedResult();

        when(
                policyEvaluator.evaluate(
                        context.policyVersion(),
                        request.governedAction(),
                        RISK_SCORE
                )
        ).thenReturn(
                expected
        );

        PolicyEvaluationResult result =
                resolver.evaluate(
                        context,
                        request,
                        riskAssessment
                );

        assertThat(
                result
        ).isSameAs(
                expected
        );

        verify(
                policyEvaluator
        ).evaluate(
                context.policyVersion(),
                request.governedAction(),
                RISK_SCORE
        );
    }

    @Test
    void preservesDefaultDeniedEvaluationResult() {
        RuntimeGovernanceRequest request =
                request(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        ACTION_ID
                );

        RuntimeGovernanceContext context =
                context();

        RiskAssessment riskAssessment =
                riskAssessment(
                        ORGANIZATION_ID,
                        ACTION_ID
                );

        PolicyEvaluationResult.DefaultDenied expected =
                new PolicyEvaluationResult.DefaultDenied(
                        POLICY_VERSION_ID,
                        RISK_SCORE,
                        new DecisionReasonCode(
                                "NO_APPLICABLE_POLICY_RULE"
                        )
                );

        when(
                policyEvaluator.evaluate(
                        context.policyVersion(),
                        request.governedAction(),
                        RISK_SCORE
                )
        ).thenReturn(
                expected
        );

        PolicyEvaluationResult result =
                resolver.evaluate(
                        context,
                        request,
                        riskAssessment
                );

        assertThat(
                result
        ).isSameAs(
                expected
        );
    }

    @Test
    void rejectsRiskAssessmentForDifferentAction() {
        RuntimeGovernanceRequest request =
                request(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        ACTION_ID
                );

        RuntimeGovernanceContext context =
                context();

        RiskAssessment riskAssessment =
                riskAssessment(
                        ORGANIZATION_ID,
                        UUID.randomUUID()
                );

        assertThatThrownBy(
                () -> resolver.evaluate(
                        context,
                        request,
                        riskAssessment
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "identity"
                );

        verifyNoInteractions(
                policyEvaluator
        );
    }

    @Test
    void rejectsRiskAssessmentFromDifferentOrganization() {
        RuntimeGovernanceRequest request =
                request(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        ACTION_ID
                );

        RuntimeGovernanceContext context =
                context();

        RiskAssessment riskAssessment =
                riskAssessment(
                        UUID.randomUUID(),
                        ACTION_ID
                );

        assertThatThrownBy(
                () -> resolver.evaluate(
                        context,
                        request,
                        riskAssessment
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "organization"
                );

        verifyNoInteractions(
                policyEvaluator
        );
    }

    @Test
    void rejectsGovernedActionForDifferentAgent() {
        RuntimeGovernanceRequest request =
                request(
                        ORGANIZATION_ID,
                        UUID.randomUUID(),
                        ACTION_ID
                );

        RuntimeGovernanceContext context =
                context();

        RiskAssessment riskAssessment =
                riskAssessment(
                        ORGANIZATION_ID,
                        ACTION_ID
                );

        assertThatThrownBy(
                () -> resolver.evaluate(
                        context,
                        request,
                        riskAssessment
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "agent"
                );

        verifyNoInteractions(
                policyEvaluator
        );
    }

    @Test
    void rejectsNullContext() {
        assertThatThrownBy(
                () -> resolver.evaluate(
                        null,
                        request(
                                ORGANIZATION_ID,
                                AGENT_ID,
                                ACTION_ID
                        ),
                        riskAssessment(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "context"
                );
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(
                () -> resolver.evaluate(
                        context(),
                        null,
                        riskAssessment(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "request"
                );
    }

    @Test
    void rejectsNullRiskAssessment() {
        assertThatThrownBy(
                () -> resolver.evaluate(
                        context(),
                        request(
                                ORGANIZATION_ID,
                                AGENT_ID,
                                ACTION_ID
                        ),
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "riskAssessment"
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

    private RuntimeGovernanceRequest request(
            UUID organizationId,
            UUID agentId,
            UUID actionId
    ) {
        GovernedAction governedAction =
                mock(
                        GovernedAction.class
                );

        when(
                governedAction.organizationId()
        ).thenReturn(
                organizationId
        );

        when(
                governedAction.agentId()
        ).thenReturn(
                agentId
        );

        when(
                governedAction.id()
        ).thenReturn(
                actionId
        );

        return new RuntimeGovernanceRequest(
                ASSESSMENT_ID,
                UUID.randomUUID(),
                governedAction,
                List.of(),
                EVALUATED_AT
        );
    }

    private RiskAssessment riskAssessment(
            UUID organizationId,
            UUID governedActionId
    ) {
        return new RiskAssessment(
                ASSESSMENT_ID,
                organizationId,
                governedActionId,
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                RISK_SCORE,
                List.of(
                        new RiskSignal(
                                new RiskSignalCode(
                                        "BASELINE_TOOL_RISK"
                                ),
                                RiskSeverity.MEDIUM,
                                25,
                                "Tool operation carries baseline runtime risk."
                        ),
                        new RiskSignal(
                                new RiskSignalCode(
                                        "FINANCIAL_IMPACT"
                                ),
                                RiskSeverity.HIGH,
                                40,
                                "Operation has deterministic financial impact."
                        )
                ),
                EVALUATED_AT
        );
    }

    private PolicyEvaluationResult matchedResult() {
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
}