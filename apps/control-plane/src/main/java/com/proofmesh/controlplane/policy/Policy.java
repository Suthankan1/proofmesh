package com.proofmesh.controlplane.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Policy(
        UUID id,
        UUID organizationId,
        PolicyName name,
        Instant createdAt
) {

    public Policy {
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
                createdAt,
                "createdAt must not be null"
        );
    }
}