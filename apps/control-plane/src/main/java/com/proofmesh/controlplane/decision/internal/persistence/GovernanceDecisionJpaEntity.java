package com.proofmesh.controlplane.decision.internal.persistence;

import com.proofmesh.controlplane.decision.DecisionOutcome;

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
        name = "governance_decisions",
        schema = "proofmesh"
)
class GovernanceDecisionJpaEntity {

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
            name = "policy_version_id",
            nullable = false,
            updatable = false
    )
    private UUID policyVersionId;

    @Column(
            name = "matched_policy_rule_id",
            updatable = false
    )
    private UUID matchedPolicyRuleId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "outcome",
            nullable = false,
            updatable = false,
            length = 32
    )
    private DecisionOutcome outcome;

    @Column(
            name = "risk_score",
            nullable = false,
            updatable = false
    )
    private short riskScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "reason_codes",
            nullable = false,
            updatable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode reasonCodes;

    @Column(
            name = "decided_at",
            nullable = false,
            updatable = false
    )
    private Instant decidedAt;

    protected GovernanceDecisionJpaEntity() {
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

    UUID policyVersionId() {
        return policyVersionId;
    }

    UUID matchedPolicyRuleId() {
        return matchedPolicyRuleId;
    }

    DecisionOutcome outcome() {
        return outcome;
    }

    int riskScore() {
        return riskScore;
    }

    JsonNode reasonCodes() {
        return reasonCodes;
    }

    Instant decidedAt() {
        return decidedAt;
    }
}