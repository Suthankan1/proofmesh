package com.proofmesh.controlplane.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingExpectation;
import com.proofmesh.controlplane.policy.AgentPolicyBindingIntegrityException;
import com.proofmesh.controlplane.policy.AgentPolicyBindingLifecycleStore;
import com.proofmesh.controlplane.policy.AgentPolicyBindingRepository;
import com.proofmesh.controlplane.policy.AgentPolicyBindingSwitchConflictException;
import com.proofmesh.controlplane.policy.AgentPolicyBindingSwitcher;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.policy.PolicyVersionRepository;
import com.proofmesh.controlplane.policy.PolicyVersionState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class DefaultAgentPolicyBindingSwitcherTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "41000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "42000000-0000-0000-0000-000000000001"
            );

    private static final UUID CURRENT_BINDING_ID =
            UUID.fromString(
                    "43000000-0000-0000-0000-000000000001"
            );

    private static final UUID NEW_BINDING_ID =
            UUID.fromString(
                    "43000000-0000-0000-0000-000000000002"
            );

    private static final PolicyVersionId CURRENT_POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "44000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyVersionId TARGET_POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "44000000-0000-0000-0000-000000000002"
                    )
            );

    private static final Instant CURRENT_ACTIVATED_AT =
            Instant.parse(
                    "2026-09-01T06:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-09-01T06:30:00Z"
            );

    private static final Instant SWITCHED_AT =
            Instant.parse(
                    "2026-09-01T07:00:00Z"
            );

    private AgentPolicyBindingRepository bindingRepository;

    private AgentPolicyBindingLifecycleStore lifecycleStore;

    private PolicyVersionRepository policyVersionRepository;

    private AgentPolicyBindingSwitcher switcher;

    @BeforeEach
    void setUp() {
        bindingRepository =
                mock(
                        AgentPolicyBindingRepository.class
                );

        lifecycleStore =
                mock(
                        AgentPolicyBindingLifecycleStore.class
                );

        policyVersionRepository =
                mock(
                        PolicyVersionRepository.class
                );

        switcher =
                new DefaultAgentPolicyBindingSwitcher(
                        bindingRepository,
                        lifecycleStore,
                        policyVersionRepository
                );
    }

    @Test
    void activatesInitialBindingWhenCallerExpectedNone() {
        stubPublishedTarget();

        when(
                lifecycleStore.lockAgent(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        ).thenReturn(
                true
        );

        when(
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        AgentPolicyBinding result =
                switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.None(),
                        SWITCHED_AT
                );

        assertThat(
                result.id()
        ).isEqualTo(
                NEW_BINDING_ID
        );

        assertThat(
                result.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                result.agentId()
        ).isEqualTo(
                AGENT_ID
        );

        assertThat(
                result.policyVersionId()
        ).isEqualTo(
                TARGET_POLICY_VERSION_ID
        );

        assertThat(
                result.activatedAt()
        ).isEqualTo(
                SWITCHED_AT
        );

        assertThat(
                result.deactivatedAt()
        ).isNull();

        assertThat(
                result.createdAt()
        ).isEqualTo(
                SWITCHED_AT
        );

        verify(
                lifecycleStore,
                never()
        ).deactivateOpenBinding(
                ORGANIZATION_ID,
                AGENT_ID,
                CURRENT_BINDING_ID,
                SWITCHED_AT
        );

        verify(
                lifecycleStore
        ).insert(
                result
        );
    }

    @Test
    void switchesExpectedCurrentBindingToNewPublishedPolicy() {
        stubPublishedTarget();

        AgentPolicyBinding current =
                currentBinding();

        when(
                lifecycleStore.lockAgent(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        ).thenReturn(
                true
        );

        when(
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
        ).thenReturn(
                Optional.of(
                        current
                )
        );

        when(
                lifecycleStore
                        .deactivateOpenBinding(
                                ORGANIZATION_ID,
                                AGENT_ID,
                                CURRENT_BINDING_ID,
                                SWITCHED_AT
                        )
        ).thenReturn(
                true
        );

        AgentPolicyBinding result =
                switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.Existing(
                                CURRENT_BINDING_ID
                        ),
                        SWITCHED_AT
                );

        assertThat(
                result.id()
        ).isEqualTo(
                NEW_BINDING_ID
        );

        assertThat(
                result.policyVersionId()
        ).isEqualTo(
                TARGET_POLICY_VERSION_ID
        );

        verify(
                lifecycleStore
        ).deactivateOpenBinding(
                ORGANIZATION_ID,
                AGENT_ID,
                CURRENT_BINDING_ID,
                SWITCHED_AT
        );

        verify(
                lifecycleStore
        ).insert(
                result
        );
    }

    @Test
    void staleExistingExpectationFailsWithoutMutation() {
        stubPublishedTarget();

        AgentPolicyBinding current =
                currentBinding();

        UUID staleBindingId =
                UUID.fromString(
                        "43000000-0000-0000-0000-000000000099"
                );

        when(
                lifecycleStore.lockAgent(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        ).thenReturn(
                true
        );

        when(
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
        ).thenReturn(
                Optional.of(
                        current
                )
        );

        assertThatThrownBy(
                () -> switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.Existing(
                                staleBindingId
                        ),
                        SWITCHED_AT
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingSwitchConflictException.class
                )
                .hasMessageContaining(
                        "changed since it was observed"
                );

        verify(
                lifecycleStore,
                never()
        ).deactivateOpenBinding(
                ORGANIZATION_ID,
                AGENT_ID,
                CURRENT_BINDING_ID,
                SWITCHED_AT
        );

        verify(
                lifecycleStore,
                never()
        ).insert(
                org.mockito.ArgumentMatchers
                        .any(
                                AgentPolicyBinding.class
                        )
        );
    }

    @Test
    void expectedNoneFailsWhenBindingAlreadyExists() {
        stubPublishedTarget();

        when(
                lifecycleStore.lockAgent(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        ).thenReturn(
                true
        );

        when(
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
        ).thenReturn(
                Optional.of(
                        currentBinding()
                )
        );

        assertThatThrownBy(
                () -> switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.None(),
                        SWITCHED_AT
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingSwitchConflictException.class
                )
                .hasMessageContaining(
                        "expected no active policy binding"
                );

        verify(
                lifecycleStore,
                never()
        ).insert(
                org.mockito.ArgumentMatchers
                        .any(
                                AgentPolicyBinding.class
                        )
        );
    }

    @Test
    void sameTargetPolicyReturnsExistingBindingWithoutCreatingHistory() {
        stubPublishedTarget(
                CURRENT_POLICY_VERSION_ID
        );

        AgentPolicyBinding current =
                currentBinding();

        when(
                lifecycleStore.lockAgent(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        ).thenReturn(
                true
        );

        when(
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
        ).thenReturn(
                Optional.of(
                        current
                )
        );

        AgentPolicyBinding result =
                switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        CURRENT_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.Existing(
                                CURRENT_BINDING_ID
                        ),
                        SWITCHED_AT
                );

        assertThat(
                result
        ).isEqualTo(
                current
        );

        verify(
                lifecycleStore,
                never()
        ).deactivateOpenBinding(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );

        verify(
                lifecycleStore,
                never()
        ).insert(
                org.mockito.ArgumentMatchers
                        .any(
                                AgentPolicyBinding.class
                        )
        );
    }

    @Test
    void rejectsUnknownAgentBeforeChangingBindingState() {
        stubPublishedTarget();

        when(
                lifecycleStore.lockAgent(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        ).thenReturn(
                false
        );

        assertThatThrownBy(
                () -> switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.None(),
                        SWITCHED_AT
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingIntegrityException.class
                )
                .hasMessageContaining(
                        "unknown organization agent"
                );

        verify(
                bindingRepository,
                never()
        ).findOpenByOrganizationIdAndAgentId(
                ORGANIZATION_ID,
                AGENT_ID
        );

        verify(
                lifecycleStore,
                never()
        ).insert(
                org.mockito.ArgumentMatchers
                        .any(
                                AgentPolicyBinding.class
                        )
        );
    }

    @Test
    void rejectsMissingTargetPolicyVersionBeforeLockingAgent() {
        when(
                policyVersionRepository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                TARGET_POLICY_VERSION_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(
                () -> switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.None(),
                        SWITCHED_AT
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingIntegrityException.class
                )
                .hasMessageContaining(
                        "does not exist"
                );

        verify(
                lifecycleStore,
                never()
        ).lockAgent(
                ORGANIZATION_ID,
                AGENT_ID
        );
    }

    @Test
    void rejectsDraftTargetPolicyVersionBeforeLockingAgent() {
        PolicyVersion target =
                mock(
                        PolicyVersion.class
                );

        when(
                target.state()
        ).thenReturn(
                PolicyVersionState.DRAFT
        );

        when(
                policyVersionRepository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                TARGET_POLICY_VERSION_ID
                        )
        ).thenReturn(
                Optional.of(
                        target
                )
        );

        assertThatThrownBy(
                () -> switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.None(),
                        SWITCHED_AT
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingIntegrityException.class
                )
                .hasMessageContaining(
                        "must be published"
                );

        verify(
                lifecycleStore,
                never()
        ).lockAgent(
                ORGANIZATION_ID,
                AGENT_ID
        );
    }

    @Test
    void rejectsActivationBeforePolicyPublication() {
        PolicyVersion target =
                mock(
                        PolicyVersion.class
                );

        when(
                target.state()
        ).thenReturn(
                PolicyVersionState.PUBLISHED
        );

        when(
                target.publishedAt()
        ).thenReturn(
                SWITCHED_AT.plusSeconds(1)
        );

        when(
                policyVersionRepository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                TARGET_POLICY_VERSION_ID
                        )
        ).thenReturn(
                Optional.of(
                        target
                )
        );

        assertThatThrownBy(
                () -> switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.None(),
                        SWITCHED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "before policy publication"
                );

        verify(
                lifecycleStore,
                never()
        ).lockAgent(
                ORGANIZATION_ID,
                AGENT_ID
        );
    }

    @Test
    void failsClosedWhenExpectedBindingCannotBeDeactivatedAfterLock() {
        stubPublishedTarget();

        AgentPolicyBinding current =
                currentBinding();

        when(
                lifecycleStore.lockAgent(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        ).thenReturn(
                true
        );

        when(
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
        ).thenReturn(
                Optional.of(
                        current
                )
        );

        when(
                lifecycleStore
                        .deactivateOpenBinding(
                                ORGANIZATION_ID,
                                AGENT_ID,
                                CURRENT_BINDING_ID,
                                SWITCHED_AT
                        )
        ).thenReturn(
                false
        );

        assertThatThrownBy(
                () -> switcher.switchBinding(
                        NEW_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        TARGET_POLICY_VERSION_ID,
                        new AgentPolicyBindingExpectation.Existing(
                                CURRENT_BINDING_ID
                        ),
                        SWITCHED_AT
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingIntegrityException.class
                )
                .hasMessageContaining(
                        "disappeared while locked"
                );

        verify(
                lifecycleStore,
                never()
        ).insert(
                org.mockito.ArgumentMatchers
                        .any(
                                AgentPolicyBinding.class
                        )
        );
    }

    @Test
    void createsReplacementUsingExactCallerSuppliedIdentityAndTime() {
        stubPublishedTarget();

        when(
                lifecycleStore.lockAgent(
                        ORGANIZATION_ID,
                        AGENT_ID
                )
        ).thenReturn(
                true
        );

        when(
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        switcher.switchBinding(
                NEW_BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                TARGET_POLICY_VERSION_ID,
                new AgentPolicyBindingExpectation.None(),
                SWITCHED_AT
        );

        ArgumentCaptor<AgentPolicyBinding> captor =
                ArgumentCaptor.forClass(
                        AgentPolicyBinding.class
                );

        verify(
                lifecycleStore
        ).insert(
                captor.capture()
        );

        AgentPolicyBinding inserted =
                captor.getValue();

        assertThat(
                inserted.id()
        ).isEqualTo(
                NEW_BINDING_ID
        );

        assertThat(
                inserted.activatedAt()
        ).isEqualTo(
                SWITCHED_AT
        );

        assertThat(
                inserted.createdAt()
        ).isEqualTo(
                SWITCHED_AT
        );

        assertThat(
                inserted.policyVersionId()
        ).isEqualTo(
                TARGET_POLICY_VERSION_ID
        );
    }

    private AgentPolicyBinding currentBinding() {
        return new AgentPolicyBinding(
                CURRENT_BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                CURRENT_POLICY_VERSION_ID,
                CURRENT_ACTIVATED_AT,
                null,
                CURRENT_ACTIVATED_AT
        );
    }

    private void stubPublishedTarget() {
        stubPublishedTarget(
                TARGET_POLICY_VERSION_ID
        );
    }

    private void stubPublishedTarget(
            PolicyVersionId policyVersionId
    ) {
        PolicyVersion target =
                mock(
                        PolicyVersion.class
                );

        when(
                target.state()
        ).thenReturn(
                PolicyVersionState.PUBLISHED
        );

        when(
                target.publishedAt()
        ).thenReturn(
                PUBLISHED_AT
        );

        when(
                policyVersionRepository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                policyVersionId
                        )
        ).thenReturn(
                Optional.of(
                        target
                )
        );
    }
}