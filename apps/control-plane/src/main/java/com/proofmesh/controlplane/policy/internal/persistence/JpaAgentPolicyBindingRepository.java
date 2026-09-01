package com.proofmesh.controlplane.policy.internal.persistence;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingRepository;

import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaAgentPolicyBindingRepository
        implements AgentPolicyBindingRepository {

    private final SpringDataAgentPolicyBindingJpaRepository
            springDataRepository;

    private final AgentPolicyBindingJpaMapper mapper;

    JpaAgentPolicyBindingRepository(
            SpringDataAgentPolicyBindingJpaRepository
                    springDataRepository,
            AgentPolicyBindingJpaMapper mapper
    ) {
        this.springDataRepository =
                Objects.requireNonNull(
                        springDataRepository,
                        "springDataRepository must not be null"
                );

        this.mapper =
                Objects.requireNonNull(
                        mapper,
                        "mapper must not be null"
                );
    }

    @Override
    public Optional<AgentPolicyBinding>
            findOpenByOrganizationIdAndAgentId(
                    UUID organizationId,
                    UUID agentId
            ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
        );

        return springDataRepository
                .findByOrganizationIdAndAgentIdAndDeactivatedAtIsNull(
                        organizationId,
                        agentId
                )
                .map(
                        mapper::toDomain
                );
    }
}