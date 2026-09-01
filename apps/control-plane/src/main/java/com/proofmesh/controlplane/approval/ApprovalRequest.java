package com.proofmesh.controlplane.approval;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalRequest(
        UUID id,
        UUID organizationId,
        UUID governedActionId,
        UUID governanceDecisionId,
        UUID agentId,
        ToolName toolName,
        OperationName operationName,
        RequestPayloadHash requestPayloadHash,
        Instant requestedAt,
        Instant expiresAt
) {

    public ApprovalRequest {
        Objects.requireNonNull(
                id,
                "id must not be null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                governedActionId,
                "governedActionId must not be null"
        );

        Objects.requireNonNull(
                governanceDecisionId,
                "governanceDecisionId must not be null"
        );

        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
        );

        Objects.requireNonNull(
                toolName,
                "toolName must not be null"
        );

        Objects.requireNonNull(
                operationName,
                "operationName must not be null"
        );

        Objects.requireNonNull(
                requestPayloadHash,
                "requestPayloadHash must not be null"
        );

        Objects.requireNonNull(
                requestedAt,
                "requestedAt must not be null"
        );

        Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );

        if (!expiresAt.isAfter(
                requestedAt
        )) {
            throw new IllegalArgumentException(
                    "expiresAt must be after requestedAt"
            );
        }
    }

    public boolean isExpiredAt(
            Instant instant
    ) {
        Objects.requireNonNull(
                instant,
                "instant must not be null"
        );

        return !instant.isBefore(
                expiresAt
        );
    }
}