package com.proofmesh.controlplane.runtimegovernance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultRuntimeGovernanceContextResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "d1000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "d2000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "d3000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "d4000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T09:30:00Z"
            );

    private RuntimeGovernancePrerequisiteResolver
            prerequisiteResolver;

    private RuntimePolicyVersionResolver
            policyVersionResolver;

    private RuntimeGovernanceContextResolver
            resolver;

    @BeforeEach
    void setUp() {
        prerequisiteResolver =
                mock(
                        RuntimeGovernancePrerequisiteResolver.class
                );

        policyVersionResolver =
                mock(
                        RuntimePolicyVersionResolver.class
                );

        resolver =
                new DefaultRuntimeGovernanceContextResolver(
                        prerequisiteResolver,
                        policyVersionResolver
                );
    }

    @Test
    void resolvesAuthoritativeRuntimeGovernanceContext() {
        RuntimeGovernanceRequest request =
                request();

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

        configureConsistentContext(
                agent,
                binding,
                policyVersion
        );

        RuntimeGovernancePrerequisiteResolution.Ready
                prerequisiteReady =
                new RuntimeGovernancePrerequisiteResolution.Ready(
                        agent,
                        binding
                );

        RuntimePolicyVersionResolution.Available
                policyAvailable =
                new RuntimePolicyVersionResolution.Available(
                        policyVersion
                );

        when(
                prerequisiteResolver.resolve(
                        request
                )
        ).thenReturn(
                prerequisiteReady
        );

        when(
                policyVersionResolver.resolve(
                        binding,
                        EVALUATED_AT
                )
        ).thenReturn(
                policyAvailable
        );

        RuntimeGovernanceContextResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernanceContextResolution
                                .Ready.class
                );

        RuntimeGovernanceContextResolution.Ready ready =
                (RuntimeGovernanceContextResolution.Ready)
                        result;

        assertThat(
                ready.context().agent()
        ).isSameAs(
                agent
        );

        assertThat(
                ready.context().policyBinding()
        ).isSameAs(
                binding
        );

        assertThat(
                ready.context().policyVersion()
        ).isSameAs(
                policyVersion
        );
    }

    @Test
    void prerequisiteFailurePropagatesWithoutPolicyLookup() {
        RuntimeGovernanceRequest request =
                request();

        RuntimeGovernancePrerequisiteResolution.Failed failure =
                new RuntimeGovernancePrerequisiteResolution.Failed(
                        RuntimeGovernanceFailureReason
                                .AGENT_NOT_ACTIVE
                );

        when(
                prerequisiteResolver.resolve(
                        request
                )
        ).thenReturn(
                failure
        );

        RuntimeGovernanceContextResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimeGovernanceContextResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .AGENT_NOT_ACTIVE
                        )
                );

        verifyNoInteractions(
                policyVersionResolver
        );
    }

    @Test
    void policyVersionFailurePropagates() {
        RuntimeGovernanceRequest request =
                request();

        Agent agent =
                mock(
                        Agent.class
                );

        AgentPolicyBinding binding =
                mock(
                        AgentPolicyBinding.class
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

        RuntimeGovernancePrerequisiteResolution.Ready
                prerequisiteReady =
                new RuntimeGovernancePrerequisiteResolution.Ready(
                        agent,
                        binding
                );

        RuntimePolicyVersionResolution.Failed policyFailure =
                new RuntimePolicyVersionResolution.Failed(
                        RuntimeGovernanceFailureReason
                                .POLICY_VERSION_UNAVAILABLE
                );

        when(
                prerequisiteResolver.resolve(
                        request
                )
        ).thenReturn(
                prerequisiteReady
        );

        when(
                policyVersionResolver.resolve(
                        binding,
                        EVALUATED_AT
                )
        ).thenReturn(
                policyFailure
        );

        RuntimeGovernanceContextResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimeGovernanceContextResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .POLICY_VERSION_UNAVAILABLE
                        )
                );
    }

    @Test
    void passesExplicitEvaluationTimeToPolicyResolver() {
        RuntimeGovernanceRequest request =
                request();

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

        configureConsistentContext(
                agent,
                binding,
                policyVersion
        );

        RuntimeGovernancePrerequisiteResolution.Ready
                prerequisiteReady =
                new RuntimeGovernancePrerequisiteResolution.Ready(
                        agent,
                        binding
                );

        RuntimePolicyVersionResolution.Available
                policyAvailable =
                new RuntimePolicyVersionResolution.Available(
                        policyVersion
                );

        when(
                prerequisiteResolver.resolve(
                        request
                )
        ).thenReturn(
                prerequisiteReady
        );

        when(
                policyVersionResolver.resolve(
                        binding,
                        EVALUATED_AT
                )
        ).thenReturn(
                policyAvailable
        );

        resolver.resolve(
                request
        );

        verify(
                policyVersionResolver
        ).resolve(
                binding,
                EVALUATED_AT
        );
    }

    @Test
    void contextRejectsPolicyVersionNotReferencedByBinding() {
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
                policyVersion.id()
        ).thenReturn(
                new PolicyVersionId(
                        UUID.randomUUID()
                )
        );

        assertThatThrownBy(
                () -> new RuntimeGovernanceContext(
                        agent,
                        binding,
                        policyVersion
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "policy version identity"
                );
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(
                () -> resolver.resolve(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "request"
                );
    }

    private RuntimeGovernanceRequest request() {
        GovernedAction governedAction =
                mock(
                        GovernedAction.class
                );

        when(
                governedAction.id()
        ).thenReturn(
                ACTION_ID
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

        return new RuntimeGovernanceRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                governedAction,
                List.of(),
                EVALUATED_AT
        );
    }

    private void configureConsistentContext(
            Agent agent,
            AgentPolicyBinding binding,
            PolicyVersion policyVersion
    ) {
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
                policyVersion.id()
        ).thenReturn(
                POLICY_VERSION_ID
        );

        when(
                policyVersion.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );
    }
}