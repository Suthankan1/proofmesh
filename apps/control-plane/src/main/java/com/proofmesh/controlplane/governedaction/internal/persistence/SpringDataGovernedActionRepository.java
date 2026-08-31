package com.proofmesh.controlplane.governedaction.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface SpringDataGovernedActionRepository
        extends JpaRepository<
                GovernedActionJpaEntity,
                UUID
        > {

    @Modifying
    @Query(
            value = """
                    INSERT INTO proofmesh.governed_actions (
                        id,
                        organization_id,
                        agent_id,
                        idempotency_key,
                        tool_name,
                        operation_name,
                        request_payload,
                        request_payload_hash,
                        created_at
                    )
                    VALUES (
                        :id,
                        :organizationId,
                        :agentId,
                        :idempotencyKey,
                        :toolName,
                        :operationName,
                        CAST(:requestPayload AS jsonb),
                        :requestPayloadHash,
                        :createdAt
                    )
                    ON CONFLICT ON CONSTRAINT
                        uq_governed_actions_idempotency
                    DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("id")
            UUID id,

            @Param("organizationId")
            UUID organizationId,

            @Param("agentId")
            UUID agentId,

            @Param("idempotencyKey")
            String idempotencyKey,

            @Param("toolName")
            String toolName,

            @Param("operationName")
            String operationName,

            @Param("requestPayload")
            String requestPayload,

            @Param("requestPayloadHash")
            String requestPayloadHash,

            @Param("createdAt")
            Instant createdAt
    );

    Optional<GovernedActionJpaEntity>
    findByIdAndOrganizationId(
            UUID id,
            UUID organizationId
    );

    Optional<GovernedActionJpaEntity>
    findByOrganizationIdAndAgentIdAndIdempotencyKey(
            UUID organizationId,
            UUID agentId,
            String idempotencyKey
    );
}