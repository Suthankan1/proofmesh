package com.proofmesh.controlplane.agent.internal.persistence;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agents")
class AgentJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(
            name = "organization_id",
            nullable = false
    )
    private UUID organizationId;

    @Column(
            name = "name",
            nullable = false,
            length = 160
    )
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 32
    )
    private AgentStatus status;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    protected AgentJpaEntity() {
    }

    AgentJpaEntity(
            UUID id,
            UUID organizationId,
            String name,
            AgentStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Agent toDomain() {
        return new Agent(
                id,
                organizationId,
                name,
                status,
                createdAt,
                updatedAt
        );
    }
}