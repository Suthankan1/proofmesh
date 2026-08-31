package com.proofmesh.controlplane.governedaction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record GovernedAction(
        UUID id,
        UUID organizationId,
        UUID agentId,
        IdempotencyKey idempotencyKey,
        ToolName toolName,
        OperationName operationName,
        CanonicalRequestPayload requestPayload,
        Instant createdAt
) {

    public GovernedAction {
        Objects.requireNonNull(
                id,
                "id must not be null"
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
                idempotencyKey,
                "idempotencyKey must not be null"
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
                requestPayload,
                "requestPayload must not be null"
        );

        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
    }

    public RequestPayloadHash requestPayloadHash() {
        return requestPayload.hash();
    }

    public boolean belongsToOrganization(
            UUID organizationId
    ) {
        return this.organizationId.equals(
                organizationId
        );
    }

    public boolean matchesRequest(
            ToolName toolName,
            OperationName operationName,
            RequestPayloadHash requestPayloadHash
    ) {
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

        return this.toolName.equals(toolName)
                && this.operationName.equals(
                        operationName
                )
                && this.requestPayloadHash()
                        .equals(
                                requestPayloadHash
                        );
    }
}