package com.proofmesh.controlplane.policy.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "agent_policy_bindings",
        schema = "proofmesh"
)
class AgentPolicyBindingJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            updatable = false
    )
    private UUID id;

    @Column(
            name = "organization_id",
            nullable = false,
            updatable = false
    )
    private UUID organizationId;

    @Column(
            name = "agent_id",
            nullable = false,
            updatable = false
    )
    private UUID agentId;

    @Column(
            name = "policy_version_id",
            nullable = false,
            updatable = false
    )
    private UUID policyVersionId;

    @Column(
            name = "activated_at",
            nullable = false,
            updatable = false
    )
    private Instant activatedAt;

    @Column(
            name = "deactivated_at"
    )
    private Instant deactivatedAt;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    protected AgentPolicyBindingJpaEntity() {
    }

    UUID id() {
        return id;
    }

    UUID organizationId() {
        return organizationId;
    }

    UUID agentId() {
        return agentId;
    }

    UUID policyVersionId() {
        return policyVersionId;
    }

    Instant activatedAt() {
        return activatedAt;
    }

    Instant deactivatedAt() {
        return deactivatedAt;
    }

    Instant createdAt() {
        return createdAt;
    }
}