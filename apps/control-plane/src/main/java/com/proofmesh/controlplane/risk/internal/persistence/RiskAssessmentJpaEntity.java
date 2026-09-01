package com.proofmesh.controlplane.risk.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "risk_assessments",
        schema = "proofmesh"
)
class RiskAssessmentJpaEntity {

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
            name = "governed_action_id",
            nullable = false,
            updatable = false
    )
    private UUID governedActionId;

    @Column(
            name = "logic_version",
            nullable = false,
            updatable = false,
            length = 64
    )
    private String logicVersion;

    @Column(
            name = "risk_score",
            nullable = false,
            updatable = false
    )
    private short riskScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "signals",
            nullable = false,
            updatable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode signals;

    @Column(
            name = "assessed_at",
            nullable = false,
            updatable = false
    )
    private Instant assessedAt;

    protected RiskAssessmentJpaEntity() {
    }

    UUID id() {
        return id;
    }

    UUID organizationId() {
        return organizationId;
    }

    UUID governedActionId() {
        return governedActionId;
    }

    String logicVersion() {
        return logicVersion;
    }

    short riskScore() {
        return riskScore;
    }

    JsonNode signals() {
        return signals;
    }

    Instant assessedAt() {
        return assessedAt;
    }
}