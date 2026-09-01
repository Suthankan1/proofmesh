package com.proofmesh.controlplane.runtimegovernance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class RuntimeGovernanceContractTest {

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

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "a4000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T09:00:00Z"
            );

    @Test
    void requestDefensivelyCopiesRiskSignals() {
        GovernedAction action =
                mockGovernedAction();

        List<RiskSignal> mutable =
                new ArrayList<>();

        mutable.add(
                signal()
        );

        RuntimeGovernanceRequest request =
                new RuntimeGovernanceRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        action,
                        mutable,
                        EVALUATED_AT
                );

        mutable.clear();

        assertThat(
                request.riskSignals()
        ).hasSize(1);
    }

    @Test
    void requestAllowsEmptySignalsForFailClosedRiskAssessment() {
        RuntimeGovernanceRequest request =
                new RuntimeGovernanceRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        mockGovernedAction(),
                        List.of(),
                        EVALUATED_AT
                );

        assertThat(
                request.riskSignals()
        ).isEmpty();
    }

    @Test
    void failedClosedCarriesExplicitReason() {
        RuntimeGovernanceResult.FailedClosed result =
                new RuntimeGovernanceResult.FailedClosed(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        ACTION_ID,
                        RuntimeGovernanceFailureReason
                                .NO_ACTIVE_POLICY_BINDING
                );

        assertThat(
                result.reason()
        ).isEqualTo(
                RuntimeGovernanceFailureReason
                        .NO_ACTIVE_POLICY_BINDING
        );
    }

    @Test
    void governedRejectsMismatchedOrganizations() {
        AgentPolicyBinding binding =
                mock(
                        AgentPolicyBinding.class
                );

        RiskAssessment assessment =
                mock(
                        RiskAssessment.class
                );

        GovernanceDecision decision =
                mock(
                        GovernanceDecision.class
                );

        UUID otherOrganizationId =
                UUID.randomUUID();

        when(
                binding.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.organizationId()
        ).thenReturn(
                otherOrganizationId
        );

        assertThatThrownBy(
                () -> new RuntimeGovernanceResult.Governed(
                        binding,
                        assessment,
                        decision
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "organization"
                );
    }

    @Test
    void governedRejectsMismatchedActions() {
        AgentPolicyBinding binding =
                mock(
                        AgentPolicyBinding.class
                );

        RiskAssessment assessment =
                mock(
                        RiskAssessment.class
                );

        GovernanceDecision decision =
                mock(
                        GovernanceDecision.class
                );

        when(
                binding.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                decision.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.governedActionId()
        ).thenReturn(
                ACTION_ID
        );

        when(
                decision.governedActionId()
        ).thenReturn(
                UUID.randomUUID()
        );

        assertThatThrownBy(
                () -> new RuntimeGovernanceResult.Governed(
                        binding,
                        assessment,
                        decision
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "action"
                );
    }

    @Test
    void governedRejectsMismatchedPolicyVersions() {
        AgentPolicyBinding binding =
                mock(
                        AgentPolicyBinding.class
                );

        RiskAssessment assessment =
                mock(
                        RiskAssessment.class
                );

        GovernanceDecision decision =
                mock(
                        GovernanceDecision.class
                );

        when(
                binding.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                decision.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.governedActionId()
        ).thenReturn(
                ACTION_ID
        );

        when(
                decision.governedActionId()
        ).thenReturn(
                ACTION_ID
        );

        when(
                binding.policyVersionId()
        ).thenReturn(
                POLICY_VERSION_ID
        );

        when(
                decision.policyVersionId()
        ).thenReturn(
                new PolicyVersionId(
                        UUID.randomUUID()
                )
        );

        assertThatThrownBy(
                () -> new RuntimeGovernanceResult.Governed(
                        binding,
                        assessment,
                        decision
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "policy version"
                );
    }

    @Test
    void governedRejectsMismatchedRiskScores() {
        AgentPolicyBinding binding =
                mock(
                        AgentPolicyBinding.class
                );

        RiskAssessment assessment =
                mock(
                        RiskAssessment.class
                );

        GovernanceDecision decision =
                mock(
                        GovernanceDecision.class
                );

        when(
                binding.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                decision.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.governedActionId()
        ).thenReturn(
                ACTION_ID
        );

        when(
                decision.governedActionId()
        ).thenReturn(
                ACTION_ID
        );

        when(
                binding.policyVersionId()
        ).thenReturn(
                POLICY_VERSION_ID
        );

        when(
                decision.policyVersionId()
        ).thenReturn(
                POLICY_VERSION_ID
        );

        when(
                assessment.riskScore()
        ).thenReturn(
                new RiskScore(
                        65
                )
        );

        when(
                decision.riskScore()
        ).thenReturn(
                new RiskScore(
                        90
                )
        );

        assertThatThrownBy(
                () -> new RuntimeGovernanceResult.Governed(
                        binding,
                        assessment,
                        decision
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "risk score"
                );
    }

    @Test
    void validGovernedResultPreservesExactProvenance() {
        AgentPolicyBinding binding =
                mock(
                        AgentPolicyBinding.class
                );

        RiskAssessment assessment =
                mock(
                        RiskAssessment.class
                );

        GovernanceDecision decision =
                mock(
                        GovernanceDecision.class
                );

        RiskScore riskScore =
                new RiskScore(
                        65
                );

        when(
                binding.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                decision.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                assessment.governedActionId()
        ).thenReturn(
                ACTION_ID
        );

        when(
                decision.governedActionId()
        ).thenReturn(
                ACTION_ID
        );

        when(
                binding.policyVersionId()
        ).thenReturn(
                POLICY_VERSION_ID
        );

        when(
                decision.policyVersionId()
        ).thenReturn(
                POLICY_VERSION_ID
        );

        when(
                assessment.riskScore()
        ).thenReturn(
                riskScore
        );

        when(
                decision.riskScore()
        ).thenReturn(
                riskScore
        );

        RuntimeGovernanceResult.Governed result =
                new RuntimeGovernanceResult.Governed(
                        binding,
                        assessment,
                        decision
                );

        assertThat(
                result.policyBinding()
        ).isSameAs(
                binding
        );

        assertThat(
                result.riskAssessment()
        ).isSameAs(
                assessment
        );

        assertThat(
                result.decision()
        ).isSameAs(
                decision
        );
    }

    private GovernedAction mockGovernedAction() {
        return mock(
                GovernedAction.class
        );
    }

    private RiskSignal signal() {
        return new RiskSignal(
                new RiskSignalCode(
                        "BASELINE_TOOL_RISK"
                ),
                RiskSeverity.MEDIUM,
                25,
                "Tool operation carries baseline runtime risk."
        );
    }
}