package com.proofmesh.controlplane.policy.internal;

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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
class DefaultAgentPolicyBindingSwitcher
        implements AgentPolicyBindingSwitcher {

    private final AgentPolicyBindingRepository
            bindingRepository;

    private final AgentPolicyBindingLifecycleStore
            lifecycleStore;

    private final PolicyVersionRepository
            policyVersionRepository;

    DefaultAgentPolicyBindingSwitcher(
            AgentPolicyBindingRepository bindingRepository,
            AgentPolicyBindingLifecycleStore lifecycleStore,
            PolicyVersionRepository policyVersionRepository
    ) {
        this.bindingRepository =
                Objects.requireNonNull(
                        bindingRepository,
                        "bindingRepository must not be null"
                );

        this.lifecycleStore =
                Objects.requireNonNull(
                        lifecycleStore,
                        "lifecycleStore must not be null"
                );

        this.policyVersionRepository =
                Objects.requireNonNull(
                        policyVersionRepository,
                        "policyVersionRepository must not be null"
                );
    }

    @Override
    @Transactional
    public AgentPolicyBinding switchBinding(
            UUID newBindingId,
            UUID organizationId,
            UUID agentId,
            PolicyVersionId targetPolicyVersionId,
            AgentPolicyBindingExpectation expectation,
            Instant switchedAt
    ) {
        Objects.requireNonNull(
                newBindingId,
                "newBindingId must not be null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
        );

        Objects.requireNonNull(
                targetPolicyVersionId,
                "targetPolicyVersionId must not be null"
        );

        Objects.requireNonNull(
                expectation,
                "expectation must not be null"
        );

        Objects.requireNonNull(
                switchedAt,
                "switchedAt must not be null"
        );

        PolicyVersion targetPolicyVersion =
                resolvePublishedTarget(
                        organizationId,
                        targetPolicyVersionId,
                        switchedAt
                );

        if (!lifecycleStore.lockAgent(
                organizationId,
                agentId
        )) {
            throw new AgentPolicyBindingIntegrityException(
                    "cannot switch policy binding for unknown organization agent"
            );
        }

        Optional<AgentPolicyBinding> current =
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                organizationId,
                                agentId
                        );

        verifyExpectation(
                expectation,
                current
        );

        if (current.isPresent()) {
            AgentPolicyBinding existing =
                    current.orElseThrow();

            if (existing.policyVersionId()
                    .equals(
                            targetPolicyVersionId
                    )) {
                return existing;
            }

            boolean deactivated =
                    lifecycleStore
                            .deactivateOpenBinding(
                                    organizationId,
                                    agentId,
                                    existing.id(),
                                    switchedAt
                            );

            if (!deactivated) {
                throw new AgentPolicyBindingIntegrityException(
                        "authoritative open policy binding disappeared while locked"
                );
            }
        }

        AgentPolicyBinding replacement =
                new AgentPolicyBinding(
                        newBindingId,
                        organizationId,
                        agentId,
                        targetPolicyVersionId,
                        switchedAt,
                        null,
                        switchedAt
                );

        lifecycleStore.insert(
                replacement
        );

        return replacement;
    }

    private PolicyVersion resolvePublishedTarget(
            UUID organizationId,
            PolicyVersionId targetPolicyVersionId,
            Instant switchedAt
    ) {
        PolicyVersion target =
                policyVersionRepository
                        .findByOrganizationIdAndId(
                                organizationId,
                                targetPolicyVersionId
                        )
                        .orElseThrow(
                                () ->
                                        new AgentPolicyBindingIntegrityException(
                                                "target policy version does not exist in organization"
                                        )
                        );

        if (target.state()
                != PolicyVersionState.PUBLISHED) {
            throw new AgentPolicyBindingIntegrityException(
                    "target policy version must be published"
            );
        }

        if (target.publishedAt() == null) {
            throw new AgentPolicyBindingIntegrityException(
                    "published target policy version is missing publication time"
            );
        }

        if (switchedAt.isBefore(
                target.publishedAt()
        )) {
            throw new IllegalArgumentException(
                    "policy binding cannot activate before policy publication"
            );
        }

        return target;
    }

    private void verifyExpectation(
            AgentPolicyBindingExpectation expectation,
            Optional<AgentPolicyBinding> current
    ) {
        switch (expectation) {
            case AgentPolicyBindingExpectation.None ignored -> {
                if (current.isPresent()) {
                    throw new AgentPolicyBindingSwitchConflictException(
                            "expected no active policy binding but one already exists"
                    );
                }
            }

            case AgentPolicyBindingExpectation.Existing existing -> {
                if (current.isEmpty()) {
                    throw new AgentPolicyBindingSwitchConflictException(
                            "expected active policy binding no longer exists"
                    );
                }

                if (!current
                        .orElseThrow()
                        .id()
                        .equals(
                                existing.bindingId()
                        )) {
                    throw new AgentPolicyBindingSwitchConflictException(
                            "active policy binding changed since it was observed"
                    );
                }
            }
        }
    }
}