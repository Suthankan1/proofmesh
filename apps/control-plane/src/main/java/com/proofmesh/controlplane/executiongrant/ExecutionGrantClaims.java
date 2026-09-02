package com.proofmesh.controlplane.executiongrant;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExecutionGrantClaims(
        ExecutionGrantId grantId,
        UUID organizationId,
        UUID agentId,
        UUID governedActionId,
        UUID governanceDecisionId,
        ToolName toolName,
        OperationName operationName,
        RequestPayloadHash requestPayloadHash,
        String issuer,
        String audience,
        Instant issuedAt,
        Instant expiresAt
) {

    public ExecutionGrantClaims {
        Objects.requireNonNull(
                grantId,
                "grantId must not be null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
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
                issuer,
                "issuer must not be null"
        );

        Objects.requireNonNull(
                audience,
                "audience must not be null"
        );

        Objects.requireNonNull(
                issuedAt,
                "issuedAt must not be null"
        );

        Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );

        if (issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "issuer must not be blank"
            );
        }

        if (audience.isBlank()) {
            throw new IllegalArgumentException(
                    "audience must not be blank"
            );
        }

        if (!expiresAt.isAfter(
                issuedAt
        )) {
            throw new IllegalArgumentException(
                    "expiresAt must be after issuedAt"
            );
        }
    }
}
