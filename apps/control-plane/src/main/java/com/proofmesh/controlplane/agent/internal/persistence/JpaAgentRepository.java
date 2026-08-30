package com.proofmesh.controlplane.agent.internal.persistence;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaAgentRepository
        implements AgentRepository {

    private final SpringDataAgentRepository
            springDataRepository;

    JpaAgentRepository(
            SpringDataAgentRepository
                    springDataRepository
    ) {
        this.springDataRepository =
                springDataRepository;
    }

    @Override
    public Optional<Agent>
    findByIdAndOrganizationId(
            UUID agentId,
            UUID organizationId
    ) {
        return springDataRepository
                .findByIdAndOrganizationId(
                        agentId,
                        organizationId
                )
                .map(
                        AgentJpaEntity::toDomain
                );
    }
}