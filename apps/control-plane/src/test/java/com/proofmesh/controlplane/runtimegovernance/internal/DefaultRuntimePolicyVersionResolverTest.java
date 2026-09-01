package com.proofmesh.controlplane.runtimegovernance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.policy.PolicyVersionRepository;
import com.proofmesh.controlplane.policy.PolicyVersionState;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class DefaultRuntimePolicyVersionResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "c1000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "c2000000-0000-0000-0000-000000000001"
            );

    private static final UUID BINDING_ID =
            UUID.fromString(
                    "c3000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "c4000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-09-01T08:30:00Z"
            );

    private static final Instant ACTIVATED_AT =
            Instant.parse(
                    "2026-09-01T08:45:00Z"
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T09:00:00Z"
            );

    private PolicyVersionRepository repository;

    private RuntimePolicyVersionResolver resolver;

    @BeforeEach
    void setUp() {
        repository =
                mock(
                        PolicyVersionRepository.class
                );

        resolver =
                new DefaultRuntimePolicyVersionResolver(
                        repository
                );
    }

    @Test
    void resolvesExactPublishedPolicyVersion() {
        AgentPolicyBinding binding =
                binding();

        PolicyVersion policyVersion =
                publishedPolicyVersion(
                        PUBLISHED_AT
                );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        POLICY_VERSION_ID
                )
        ).thenReturn(
                Optional.of(
                        policyVersion
                )
        );

        RuntimePolicyVersionResolution result =
                resolver.resolve(
                        binding,
                        EVALUATED_AT
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimePolicyVersionResolution
                                .Available.class
                );

        RuntimePolicyVersionResolution.Available available =
                (RuntimePolicyVersionResolution.Available)
                        result;

        assertThat(
                available.policyVersion()
        ).isSameAs(
                policyVersion
        );
    }

    @Test
    void missingPolicyVersionFailsClosed() {
        AgentPolicyBinding binding =
                binding();

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        POLICY_VERSION_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        RuntimePolicyVersionResolution result =
                resolver.resolve(
                        binding,
                        EVALUATED_AT
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimePolicyVersionResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .POLICY_VERSION_UNAVAILABLE
                        )
                );
    }

    @Test
    void draftPolicyVersionFailsClosed() {
        AgentPolicyBinding binding =
                binding();

        PolicyVersion policyVersion =
                mock(
                        PolicyVersion.class
                );

        when(
                policyVersion.state()
        ).thenReturn(
                PolicyVersionState.DRAFT
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        POLICY_VERSION_ID
                )
        ).thenReturn(
                Optional.of(
                        policyVersion
                )
        );

        RuntimePolicyVersionResolution result =
                resolver.resolve(
                        binding,
                        EVALUATED_AT
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimePolicyVersionResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .POLICY_VERSION_NOT_PUBLISHED
                        )
                );
    }

    @Test
    void publishedStateWithoutPublicationTimestampFailsClosed() {
        AgentPolicyBinding binding =
                binding();

        PolicyVersion policyVersion =
                mock(
                        PolicyVersion.class
                );

        when(
                policyVersion.state()
        ).thenReturn(
                PolicyVersionState.PUBLISHED
        );

        when(
                policyVersion.publishedAt()
        ).thenReturn(
                null
        );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        POLICY_VERSION_ID
                )
        ).thenReturn(
                Optional.of(
                        policyVersion
                )
        );

        RuntimePolicyVersionResolution result =
                resolver.resolve(
                        binding,
                        EVALUATED_AT
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimePolicyVersionResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .POLICY_VERSION_NOT_PUBLISHED
                        )
                );
    }

    @Test
    void policyPublishedAfterEvaluationTimeFailsClosed() {
        AgentPolicyBinding binding =
                binding();

        PolicyVersion policyVersion =
                publishedPolicyVersion(
                        EVALUATED_AT.plusSeconds(
                                1
                        )
                );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        POLICY_VERSION_ID
                )
        ).thenReturn(
                Optional.of(
                        policyVersion
                )
        );

        RuntimePolicyVersionResolution result =
                resolver.resolve(
                        binding,
                        EVALUATED_AT
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimePolicyVersionResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .POLICY_VERSION_NOT_PUBLISHED
                        )
                );
    }

    @Test
    void performsTenantScopedExactVersionLookup() {
        AgentPolicyBinding binding =
                binding();

        PolicyVersion policyVersion =
                publishedPolicyVersion(
                        PUBLISHED_AT
                );

        when(
                repository.findByOrganizationIdAndId(
                        ORGANIZATION_ID,
                        POLICY_VERSION_ID
                )
        ).thenReturn(
                Optional.of(
                        policyVersion
                )
        );

        resolver.resolve(
                binding,
                EVALUATED_AT
        );

        verify(
                repository
        ).findByOrganizationIdAndId(
                ORGANIZATION_ID,
                POLICY_VERSION_ID
        );
    }

    @Test
    void rejectsNullBinding() {
        assertThatThrownBy(
                () -> resolver.resolve(
                        null,
                        EVALUATED_AT
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "binding"
                );
    }

    @Test
    void rejectsNullEvaluationTime() {
        assertThatThrownBy(
                () -> resolver.resolve(
                        binding(),
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "evaluatedAt"
                );
    }

    private AgentPolicyBinding binding() {
        return new AgentPolicyBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT,
                null,
                ACTIVATED_AT
        );
    }

    private PolicyVersion publishedPolicyVersion(
            Instant publishedAt
    ) {
        PolicyVersion policyVersion =
                mock(
                        PolicyVersion.class
                );

        when(
                policyVersion.state()
        ).thenReturn(
                PolicyVersionState.PUBLISHED
        );

        when(
                policyVersion.publishedAt()
        ).thenReturn(
                publishedAt
        );

        return policyVersion;
    }
}