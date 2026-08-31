package com.proofmesh.controlplane.governedaction;

import java.util.Objects;
import java.util.UUID;

public record CreateGovernedActionCommand(
        UUID organizationId,
        UUID agentId,
        IdempotencyKey idempotencyKey,
        ToolName toolName,
        OperationName operationName,
        String requestPayloadJson
) {

    public CreateGovernedActionCommand {
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
                requestPayloadJson,
                "requestPayloadJson must not be null"
        );
    }
}