package com.proofmesh.controlplane.agent.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataAgentRepository
        extends JpaRepository<
                AgentJpaEntity,
                UUID
        > {

    Optional<AgentJpaEntity>
    findByIdAndOrganizationId(
            UUID id,
            UUID organizationId
    );
}