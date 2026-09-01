package com.proofmesh.controlplane.policy.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataAgentPolicyBindingJpaRepository
        extends JpaRepository<
                AgentPolicyBindingJpaEntity,
                UUID
        > {

    Optional<AgentPolicyBindingJpaEntity>
            findByOrganizationIdAndAgentIdAndDeactivatedAtIsNull(
                    UUID organizationId,
                    UUID agentId
            );
}