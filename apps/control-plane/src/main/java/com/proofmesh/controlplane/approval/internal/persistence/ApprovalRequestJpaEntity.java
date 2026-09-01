package com.proofmesh.controlplane.approval.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "approval_requests",
        schema = "proofmesh"
)
class ApprovalRequestJpaEntity {

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
            name = "governance_decision_id",
            nullable = false,
            updatable = false
    )
    private UUID governanceDecisionId;

    @Column(
            name = "agent_id",
            nullable = false,
            updatable = false
    )
    private UUID agentId;

    @Column(
            name = "tool_name",
            nullable = false,
            updatable = false
    )
    private String toolName;

    @Column(
            name = "operation_name",
            nullable = false,
            updatable = false
    )
    private String operationName;

    @Column(
            name = "request_payload_hash",
            nullable = false,
            updatable = false,
            length = 64
    )
    private String requestPayloadHash;

    @Column(
            name = "requested_at",
            nullable = false,
            updatable = false
    )
    private Instant requestedAt;

    @Column(
            name = "expires_at",
            nullable = false,
            updatable = false
    )
    private Instant expiresAt;

    @Column(
            name = "status",
            nullable = false,
            updatable = false,
            length = 32
    )
    private String status;

    @Column(
            name = "actor_id",
            updatable = false,
            length = 255
    )
    private String actorId;

    @Column(
            name = "rationale",
            updatable = false,
            length = 1000
    )
    private String rationale;

    @Column(
            name = "decided_at",
            updatable = false
    )
    private Instant decidedAt;

    @Column(
            name = "expired_at",
            updatable = false
    )
    private Instant expiredAt;

    protected ApprovalRequestJpaEntity() {
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

    UUID governanceDecisionId() {
        return governanceDecisionId;
    }

    UUID agentId() {
        return agentId;
    }

    String toolName() {
        return toolName;
    }

    String operationName() {
        return operationName;
    }

    String requestPayloadHash() {
        return requestPayloadHash;
    }

    Instant requestedAt() {
        return requestedAt;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    String status() {
        return status;
    }

    String actorId() {
        return actorId;
    }

    String rationale() {
        return rationale;
    }

    Instant decidedAt() {
        return decidedAt;
    }

    Instant expiredAt() {
        return expiredAt;
    }
}