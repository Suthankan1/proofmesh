package com.proofmesh.controlplane.governedaction.internal.persistence;

import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadCanonicalizer;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;
import com.proofmesh.controlplane.governedaction.GovernedActionIntegrityException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "governed_actions")
class GovernedActionJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(
            name = "organization_id",
            nullable = false
    )
    private UUID organizationId;

    @Column(
            name = "agent_id",
            nullable = false
    )
    private UUID agentId;

    @Column(
            name = "idempotency_key",
            nullable = false,
            length = 128
    )
    private String idempotencyKey;

    @Column(
            name = "tool_name",
            nullable = false,
            length = 160
    )
    private String toolName;

    @Column(
            name = "operation_name",
            nullable = false,
            length = 160
    )
    private String operationName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "request_payload",
            nullable = false,
            columnDefinition = "jsonb"
    )
    private JsonNode requestPayload;

    @Column(
            name = "request_payload_hash",
            nullable = false,
            length = 64
    )
    private String requestPayloadHash;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    protected GovernedActionJpaEntity() {
    }

    private GovernedActionJpaEntity(
            UUID id,
            UUID organizationId,
            UUID agentId,
            String idempotencyKey,
            String toolName,
            String operationName,
            JsonNode requestPayload,
            String requestPayloadHash,
            Instant createdAt
    ) {
        this.id = id;
        this.organizationId = organizationId;
        this.agentId = agentId;
        this.idempotencyKey = idempotencyKey;
        this.toolName = toolName;
        this.operationName = operationName;
        this.requestPayload = requestPayload;
        this.requestPayloadHash =
                requestPayloadHash;
        this.createdAt = createdAt;
    }

    static GovernedActionJpaEntity fromDomain(
            GovernedAction governedAction,
            JsonMapper jsonMapper
    ) {
        final JsonNode payload;

        try {
            payload =
                    jsonMapper.readTree(
                            governedAction
                                    .requestPayload()
                                    .canonicalJson()
                    );
        } catch (JacksonException exception) {
            throw new GovernedActionIntegrityException(
                    "Canonical request payload could not be parsed",
                    exception
            );
        }

        if (!payload.isObject()) {
            throw new GovernedActionIntegrityException(
                    "Governed action request payload must be a JSON object"
            );
        }

        return new GovernedActionJpaEntity(
                governedAction.id(),
                governedAction.organizationId(),
                governedAction.agentId(),
                governedAction
                        .idempotencyKey()
                        .value(),
                governedAction
                        .toolName()
                        .value(),
                governedAction
                        .operationName()
                        .value(),
                payload,
                governedAction
                        .requestPayloadHash()
                        .value(),
                governedAction.createdAt()
        );
    }

    GovernedAction toDomain(
            RequestPayloadCanonicalizer canonicalizer
    ) {
        CanonicalRequestPayload canonicalPayload =
                canonicalizer.canonicalize(
                        requestPayload.toString()
                );

        RequestPayloadHash storedHash =
                new RequestPayloadHash(
                        requestPayloadHash
                );

        if (!canonicalPayload
                .hash()
                .equals(storedHash)) {
            throw new GovernedActionIntegrityException(
                    "Persisted request payload does not match its stored hash"
            );
        }

        return new GovernedAction(
                id,
                organizationId,
                agentId,
                new IdempotencyKey(
                        idempotencyKey
                ),
                new ToolName(
                        toolName
                ),
                new OperationName(
                        operationName
                ),
                new CanonicalRequestPayload(
                        canonicalPayload.canonicalJson(),
                        storedHash
                ),
                createdAt
        );
    }
}