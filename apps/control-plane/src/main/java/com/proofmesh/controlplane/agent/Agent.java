package com.proofmesh.controlplane.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Agent(
        UUID id,
        UUID organizationId,
        String name,
        AgentStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public Agent {
        Objects.requireNonNull(
                id,
                "id must not be null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                name,
                "name must not be null"
        );

        Objects.requireNonNull(
                status,
                "status must not be null"
        );

        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );

        Objects.requireNonNull(
                updatedAt,
                "updatedAt must not be null"
        );

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "name must not be blank"
            );
        }
    }

    public boolean isActive() {
        return status == AgentStatus.ACTIVE;
    }
}