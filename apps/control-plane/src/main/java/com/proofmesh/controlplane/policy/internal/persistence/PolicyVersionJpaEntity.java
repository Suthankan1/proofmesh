package com.proofmesh.controlplane.policy.internal.persistence;

import com.proofmesh.controlplane.policy.PolicyVersionState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "policy_versions",
        schema = "proofmesh"
)
class PolicyVersionJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            updatable = false
    )
    private UUID id;

    @Column(
            name = "policy_id",
            nullable = false,
            updatable = false
    )
    private UUID policyId;

    @Column(
            name = "organization_id",
            nullable = false,
            updatable = false
    )
    private UUID organizationId;

    @Column(
            name = "version_number",
            nullable = false,
            updatable = false
    )
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "state",
            nullable = false,
            length = 32
    )
    private PolicyVersionState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "definition",
            nullable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode definition;

    @Column(
            name = "definition_hash",
            length = 64
    )
    private String definitionHash;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "published_at"
    )
    private Instant publishedAt;

    protected PolicyVersionJpaEntity() {
    }

    UUID id() {
        return id;
    }

    UUID policyId() {
        return policyId;
    }

    UUID organizationId() {
        return organizationId;
    }

    int versionNumber() {
        return versionNumber;
    }

    PolicyVersionState state() {
        return state;
    }

    JsonNode definition() {
        return definition;
    }

    String definitionHash() {
        return definitionHash;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant publishedAt() {
        return publishedAt;
    }
}