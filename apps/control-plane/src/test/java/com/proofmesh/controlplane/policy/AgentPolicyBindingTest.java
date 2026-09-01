package com.proofmesh.controlplane.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class AgentPolicyBindingTest {

    private static final UUID BINDING_ID =
            UUID.fromString(
                    "11000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "12000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "13000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "14000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant ACTIVATED_AT =
            Instant.parse(
                    "2026-09-01T06:00:00Z"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-09-01T06:00:01Z"
            );

    @Test
    void openBindingIsActiveFromActivationTime() {
        AgentPolicyBinding binding =
                openBinding();

        assertThat(
                binding.isActiveAt(
                        ACTIVATED_AT
                )
        ).isTrue();

        assertThat(
                binding.isActiveAt(
                        ACTIVATED_AT.plusSeconds(1)
                )
        ).isTrue();
    }

    @Test
    void bindingIsNotActiveBeforeActivationTime() {
        AgentPolicyBinding binding =
                openBinding();

        assertThat(
                binding.isActiveAt(
                        ACTIVATED_AT.minusNanos(1)
                )
        ).isFalse();
    }

    @Test
    void deactivatedBindingUsesExclusiveEndTime() {
        Instant deactivatedAt =
                ACTIVATED_AT.plusSeconds(60);

        AgentPolicyBinding binding =
                openBinding()
                        .deactivate(
                                deactivatedAt
                        );

        assertThat(
                binding.isActiveAt(
                        deactivatedAt.minusNanos(1)
                )
        ).isTrue();

        assertThat(
                binding.isActiveAt(
                        deactivatedAt
                )
        ).isFalse();
    }

    @Test
    void deactivationPreservesBindingIdentity() {
        Instant deactivatedAt =
                ACTIVATED_AT.plusSeconds(60);

        AgentPolicyBinding original =
                openBinding();

        AgentPolicyBinding deactivated =
                original.deactivate(
                        deactivatedAt
                );

        assertThat(
                deactivated.id()
        ).isEqualTo(
                original.id()
        );

        assertThat(
                deactivated.organizationId()
        ).isEqualTo(
                original.organizationId()
        );

        assertThat(
                deactivated.agentId()
        ).isEqualTo(
                original.agentId()
        );

        assertThat(
                deactivated.policyVersionId()
        ).isEqualTo(
                original.policyVersionId()
        );

        assertThat(
                deactivated.activatedAt()
        ).isEqualTo(
                original.activatedAt()
        );

        assertThat(
                deactivated.deactivatedAt()
        ).isEqualTo(
                deactivatedAt
        );
    }

    @Test
    void rejectsDeactivationBeforeActivation() {
        assertThatThrownBy(
                () -> openBinding()
                        .deactivate(
                                ACTIVATED_AT.minusSeconds(1)
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsSecondDeactivation() {
        AgentPolicyBinding deactivated =
                openBinding()
                        .deactivate(
                                ACTIVATED_AT.plusSeconds(60)
                        );

        assertThatThrownBy(
                () -> deactivated
                        .deactivate(
                                ACTIVATED_AT.plusSeconds(120)
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "already deactivated"
                );
    }

    private AgentPolicyBinding openBinding() {
        return new AgentPolicyBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT,
                null,
                CREATED_AT
        );
    }
}