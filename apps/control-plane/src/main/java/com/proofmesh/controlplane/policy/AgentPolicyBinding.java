package com.proofmesh.controlplane.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentPolicyBinding(
        UUID id,
        UUID organizationId,
        UUID agentId,
        PolicyVersionId policyVersionId,
        Instant activatedAt,
        Instant deactivatedAt,
        Instant createdAt
) {

    public AgentPolicyBinding {
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
                policyVersionId,
                "policyVersionId must not be null"
        );

        Objects.requireNonNull(
                activatedAt,
                "activatedAt must not be null"
        );

        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );

        if (deactivatedAt != null
                && deactivatedAt.isBefore(
                        activatedAt
                )) {
            throw new IllegalArgumentException(
                    "deactivatedAt must not be before activatedAt"
            );
        }
    }

    public boolean isOpen() {
        return deactivatedAt == null;
    }

    public boolean isActiveAt(
            Instant instant
    ) {
        Objects.requireNonNull(
                instant,
                "instant must not be null"
        );

        if (instant.isBefore(
                activatedAt
        )) {
            return false;
        }

        return deactivatedAt == null
                || instant.isBefore(
                        deactivatedAt
                );
    }

    public AgentPolicyBinding deactivate(
            Instant deactivatedAt
    ) {
        Objects.requireNonNull(
                deactivatedAt,
                "deactivatedAt must not be null"
        );

        if (!isOpen()) {
            throw new IllegalStateException(
                    "agent policy binding is already deactivated"
            );
        }

        if (deactivatedAt.isBefore(
                activatedAt
        )) {
            throw new IllegalArgumentException(
                    "deactivation time must not be before activation time"
            );
        }

        return new AgentPolicyBinding(
                id,
                organizationId,
                agentId,
                policyVersionId,
                activatedAt,
                deactivatedAt,
                createdAt
        );
    }
}