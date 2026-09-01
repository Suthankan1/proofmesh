package com.proofmesh.controlplane.policy.internal;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingIntegrityException;
import com.proofmesh.controlplane.policy.AgentPolicyBindingRepository;
import com.proofmesh.controlplane.policy.AgentPolicyBindingResolution;
import com.proofmesh.controlplane.policy.AgentPolicyBindingResolver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
class DefaultAgentPolicyBindingResolver
        implements AgentPolicyBindingResolver {

    private final AgentPolicyBindingRepository repository;

    DefaultAgentPolicyBindingResolver(
            AgentPolicyBindingRepository repository
    ) {
        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository must not be null"
                );
    }

    @Override
    public AgentPolicyBindingResolution resolve(
            UUID organizationId,
            UUID agentId,
            Instant asOf
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
        );

        Objects.requireNonNull(
                asOf,
                "asOf must not be null"
        );

        Optional<AgentPolicyBinding> candidate =
                repository
                        .findOpenByOrganizationIdAndAgentId(
                                organizationId,
                                agentId
                        );

        if (candidate.isEmpty()) {
            return new AgentPolicyBindingResolution
                    .NoBinding(
                            organizationId,
                            agentId
                    );
        }

        AgentPolicyBinding binding =
                candidate.orElseThrow();

        validateIdentity(
                organizationId,
                agentId,
                binding
        );

        if (!binding.isOpen()) {
            throw new AgentPolicyBindingIntegrityException(
                    "open binding repository returned a deactivated binding"
            );
        }

        if (!binding.isActiveAt(
                asOf
        )) {
            return new AgentPolicyBindingResolution
                    .NoBinding(
                            organizationId,
                            agentId
                    );
        }

        return new AgentPolicyBindingResolution
                .ActiveBinding(
                        binding
                );
    }

    private void validateIdentity(
            UUID organizationId,
            UUID agentId,
            AgentPolicyBinding binding
    ) {
        if (!binding.organizationId()
                .equals(
                        organizationId
                )) {
            throw new AgentPolicyBindingIntegrityException(
                    "resolved policy binding belongs to a different organization"
            );
        }

        if (!binding.agentId()
                .equals(
                        agentId
                )) {
            throw new AgentPolicyBindingIntegrityException(
                    "resolved policy binding belongs to a different agent"
            );
        }
    }
}