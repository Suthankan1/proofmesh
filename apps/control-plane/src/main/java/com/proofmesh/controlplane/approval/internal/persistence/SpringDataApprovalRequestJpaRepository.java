package com.proofmesh.controlplane.approval.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface SpringDataApprovalRequestJpaRepository
        extends JpaRepository<
                ApprovalRequestJpaEntity,
                UUID
        > {

    Optional<ApprovalRequestJpaEntity>
            findByOrganizationIdAndId(
                    UUID organizationId,
                    UUID id
            );

    Optional<ApprovalRequestJpaEntity>
            findByOrganizationIdAndGovernanceDecisionId(
                    UUID organizationId,
                    UUID governanceDecisionId
            );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    INSERT INTO proofmesh.approval_requests (
                        id,
                        organization_id,
                        governed_action_id,
                        governance_decision_id,
                        agent_id,
                        tool_name,
                        operation_name,
                        request_payload_hash,
                        requested_at,
                        expires_at,
                        status
                    )
                    VALUES (
                        :id,
                        :organizationId,
                        :governedActionId,
                        :governanceDecisionId,
                        :agentId,
                        :toolName,
                        :operationName,
                        :requestPayloadHash,
                        :requestedAt,
                        :expiresAt,
                        'PENDING'
                    )
                    ON CONFLICT
                    ON CONSTRAINT uq_approval_requests_decision
                    DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("id")
            UUID id,

            @Param("organizationId")
            UUID organizationId,

            @Param("governedActionId")
            UUID governedActionId,

            @Param("governanceDecisionId")
            UUID governanceDecisionId,

            @Param("agentId")
            UUID agentId,

            @Param("toolName")
            String toolName,

            @Param("operationName")
            String operationName,

            @Param("requestPayloadHash")
            String requestPayloadHash,

            @Param("requestedAt")
            Instant requestedAt,

            @Param("expiresAt")
            Instant expiresAt
    );
}