package com.proofmesh.controlplane.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingIntegrityException;
import com.proofmesh.controlplane.policy.AgentPolicyBindingRepository;
import com.proofmesh.controlplane.policy.AgentPolicyBindingResolution;
import com.proofmesh.controlplane.policy.AgentPolicyBindingResolver;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class DefaultAgentPolicyBindingResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "21000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "21000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "22000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_AGENT_ID =
            UUID.fromString(
                    "22000000-0000-0000-0000-000000000002"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "23000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant ACTIVATED_AT =
            Instant.parse(
                    "2026-09-01T06:00:00Z"
            );

    private static final Instant AS_OF =
            Instant.parse(
                    "2026-09-01T07:00:00Z"
            );

    @Test
    void resolvesActiveOpenBinding() {
        AgentPolicyBinding binding =
                binding(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        ACTIVATED_AT,
                        null
                );

        AgentPolicyBindingResolver resolver =
                resolverReturning(
                        binding
                );

        AgentPolicyBindingResolution result =
                resolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        AS_OF
                );

        assertThat(result)
                .isInstanceOf(
                        AgentPolicyBindingResolution
                                .ActiveBinding.class
                );

        AgentPolicyBindingResolution.ActiveBinding active =
                (AgentPolicyBindingResolution.ActiveBinding)
                        result;

        assertThat(
                active.binding()
        ).isEqualTo(
                binding
        );
    }

    @Test
    void resolvesNoBindingWhenRepositoryIsEmpty() {
        AgentPolicyBindingResolver resolver =
                resolverReturningNothing();

        AgentPolicyBindingResolution result =
                resolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        AS_OF
                );

        assertThat(result)
                .isEqualTo(
                        new AgentPolicyBindingResolution
                                .NoBinding(
                                        ORGANIZATION_ID,
                                        AGENT_ID
                                )
                );
    }

    @Test
    void futureOpenBindingDoesNotBecomeActiveEarly() {
        AgentPolicyBinding future =
                binding(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        AS_OF.plusSeconds(60),
                        null
                );

        AgentPolicyBindingResolver resolver =
                resolverReturning(
                        future
                );

        AgentPolicyBindingResolution result =
                resolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        AS_OF
                );

        assertThat(result)
                .isInstanceOf(
                        AgentPolicyBindingResolution
                                .NoBinding.class
                );
    }

    @Test
    void rejectsBindingFromDifferentOrganization() {
        AgentPolicyBinding wrongTenant =
                binding(
                        OTHER_ORGANIZATION_ID,
                        AGENT_ID,
                        ACTIVATED_AT,
                        null
                );

        AgentPolicyBindingResolver resolver =
                resolverReturning(
                        wrongTenant
                );

        assertThatThrownBy(
                () -> resolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        AS_OF
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingIntegrityException.class
                )
                .hasMessageContaining(
                        "different organization"
                );
    }

    @Test
    void rejectsBindingForDifferentAgent() {
        AgentPolicyBinding wrongAgent =
                binding(
                        ORGANIZATION_ID,
                        OTHER_AGENT_ID,
                        ACTIVATED_AT,
                        null
                );

        AgentPolicyBindingResolver resolver =
                resolverReturning(
                        wrongAgent
                );

        assertThatThrownBy(
                () -> resolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        AS_OF
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingIntegrityException.class
                )
                .hasMessageContaining(
                        "different agent"
                );
    }

    @Test
    void rejectsDeactivatedBindingReturnedByOpenRepository() {
        AgentPolicyBinding deactivated =
                binding(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        ACTIVATED_AT,
                        AS_OF.plusSeconds(60)
                );

        AgentPolicyBindingResolver resolver =
                resolverReturning(
                        deactivated
                );

        assertThatThrownBy(
                () -> resolver.resolve(
                        ORGANIZATION_ID,
                        AGENT_ID,
                        AS_OF
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingIntegrityException.class
                )
                .hasMessageContaining(
                        "deactivated binding"
                );
    }

    private AgentPolicyBindingResolver resolverReturning(
            AgentPolicyBinding binding
    ) {
        AgentPolicyBindingRepository repository =
                new StubRepository(
                        Optional.of(
                                binding
                        )
                );

        return new DefaultAgentPolicyBindingResolver(
                repository
        );
    }

    private AgentPolicyBindingResolver resolverReturningNothing() {
        return new DefaultAgentPolicyBindingResolver(
                new StubRepository(
                        Optional.empty()
                )
        );
    }

    private AgentPolicyBinding binding(
            UUID organizationId,
            UUID agentId,
            Instant activatedAt,
            Instant deactivatedAt
    ) {
        return new AgentPolicyBinding(
                UUID.randomUUID(),
                organizationId,
                agentId,
                POLICY_VERSION_ID,
                activatedAt,
                deactivatedAt,
                activatedAt.plusSeconds(1)
        );
    }

    private record StubRepository(
            Optional<AgentPolicyBinding> result
    ) implements AgentPolicyBindingRepository {

        @Override
        public Optional<AgentPolicyBinding>
                findOpenByOrganizationIdAndAgentId(
                        UUID organizationId,
                        UUID agentId
                ) {
            return result;
        }
    }
}