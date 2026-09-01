package com.proofmesh.controlplane.runtimegovernance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentResolution;
import com.proofmesh.controlplane.agent.AgentResolver;
import com.proofmesh.controlplane.agent.AgentStatus;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingResolution;
import com.proofmesh.controlplane.policy.AgentPolicyBindingResolver;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultRuntimeGovernancePrerequisiteResolverTest {

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

    private static final UUID BINDING_ID =
            UUID.fromString(
                    "b4000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "b5000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant AGENT_CREATED_AT =
            Instant.parse(
                    "2026-09-01T08:00:00Z"
            );

    private static final Instant BINDING_ACTIVATED_AT =
            Instant.parse(
                    "2026-09-01T08:30:00Z"
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T09:00:00Z"
            );

    private AgentResolver agentResolver;

    private AgentPolicyBindingResolver
            policyBindingResolver;

    private RuntimeGovernancePrerequisiteResolver
            resolver;

    @BeforeEach
    void setUp() {
        agentResolver =
                mock(
                        AgentResolver.class
                );

        policyBindingResolver =
                mock(
                        AgentPolicyBindingResolver.class
                );

        resolver =
                new DefaultRuntimeGovernancePrerequisiteResolver(
                        agentResolver,
                        policyBindingResolver
                );
    }

    @Test
    void resolvesActiveAgentAndActivePolicyBinding() {
        Agent agent =
                activeAgent();

        AgentPolicyBinding binding =
                activeBinding();

        RuntimeGovernanceRequest request =
                request();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Active(
                        agent
                )
        );

        when(
                policyBindingResolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        EVALUATED_AT
                )
        ).thenReturn(
                new AgentPolicyBindingResolution.ActiveBinding(
                        binding
                )
        );

        RuntimeGovernancePrerequisiteResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernancePrerequisiteResolution
                                .Ready.class
                );

        RuntimeGovernancePrerequisiteResolution.Ready ready =
                (RuntimeGovernancePrerequisiteResolution.Ready)
                        result;

        assertThat(
                ready.agent()
        ).isEqualTo(
                agent
        );

        assertThat(
                ready.policyBinding()
        ).isEqualTo(
                binding
        );
    }

    @Test
    void unknownAgentFailsClosedBeforeBindingResolution() {
        RuntimeGovernanceRequest request =
                request();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Unknown(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        );

        RuntimeGovernancePrerequisiteResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimeGovernancePrerequisiteResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .AGENT_NOT_ACTIVE
                        )
                );

        verifyNoInteractions(
                policyBindingResolver
        );
    }

    @Test
    void disabledAgentFailsClosedBeforeBindingResolution() {
        Agent disabledAgent =
                new Agent(
                        AGENT_ID,
                        ORGANIZATION_ID,
                        "Disabled Runtime Agent",
                        AgentStatus.DISABLED,
                        AGENT_CREATED_AT,
                        AGENT_CREATED_AT
                );

        RuntimeGovernanceRequest request =
                request();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Disabled(
                        disabledAgent
                )
        );

        RuntimeGovernancePrerequisiteResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimeGovernancePrerequisiteResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .AGENT_NOT_ACTIVE
                        )
                );

        verifyNoInteractions(
                policyBindingResolver
        );
    }

    @Test
    void missingPolicyBindingFailsClosed() {
        Agent agent =
                activeAgent();

        RuntimeGovernanceRequest request =
                request();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Active(
                        agent
                )
        );

        when(
                policyBindingResolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        EVALUATED_AT
                )
        ).thenReturn(
                new AgentPolicyBindingResolution.NoBinding(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        );

        RuntimeGovernancePrerequisiteResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimeGovernancePrerequisiteResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .NO_ACTIVE_POLICY_BINDING
                        )
                );
    }

    @Test
    void resolvesBindingAtExplicitEvaluationTime() {
        Agent agent =
                activeAgent();

        AgentPolicyBinding binding =
                activeBinding();

        RuntimeGovernanceRequest request =
                request();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Active(
                        agent
                )
        );

        when(
                policyBindingResolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        EVALUATED_AT
                )
        ).thenReturn(
                new AgentPolicyBindingResolution.ActiveBinding(
                        binding
                )
        );

        resolver.resolve(
                request
        );

        verify(
                policyBindingResolver
        ).resolve(
                ORGANIZATION_ID,
                AGENT_ID,
                EVALUATED_AT
        );
    }

    @Test
    void readyResolutionRejectsCrossTenantBinding() {
        Agent agent =
                activeAgent();

        AgentPolicyBinding foreignBinding =
                new AgentPolicyBinding(
                        BINDING_ID,
                        UUID.randomUUID(),
                        AGENT_ID,
                        POLICY_VERSION_ID,
                        BINDING_ACTIVATED_AT,
                        null,
                        BINDING_ACTIVATED_AT
                );

        assertThatThrownBy(
                () ->
                        new RuntimeGovernancePrerequisiteResolution.Ready(
                                agent,
                                foreignBinding
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "organization"
                );
    }

    private RuntimeGovernanceRequest request() {
        GovernedAction action =
                mock(
                        GovernedAction.class
                );

        when(
                action.id()
        ).thenReturn(
                ACTION_ID
        );

        when(
                action.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                action.agentId()
        ).thenReturn(
                AGENT_ID
        );

        return new RuntimeGovernanceRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                action,
                List.of(),
                EVALUATED_AT
        );
    }

    private Agent activeAgent() {
        return new Agent(
                AGENT_ID,
                ORGANIZATION_ID,
                "Runtime Governance Agent",
                AgentStatus.ACTIVE,
                AGENT_CREATED_AT,
                AGENT_CREATED_AT
        );
    }

    private AgentPolicyBinding activeBinding() {
        return new AgentPolicyBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                BINDING_ACTIVATED_AT,
                null,
                BINDING_ACTIVATED_AT
        );
    }
}