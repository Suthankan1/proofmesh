package com.proofmesh.controlplane.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PolicyVersion(
        PolicyVersionId id,
        UUID policyId,
        UUID organizationId,
        PolicyVersionNumber versionNumber,
        PolicyVersionState state,
        PolicyDefinition definition,
        PolicyDefinitionHash definitionHash,
        Instant createdAt,
        Instant publishedAt
) {

    public PolicyVersion {
        Objects.requireNonNull(
                id,
                "id must not be null"
        );

        Objects.requireNonNull(
                policyId,
                "policyId must not be null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                versionNumber,
                "versionNumber must not be null"
        );

        Objects.requireNonNull(
                state,
                "state must not be null"
        );

        Objects.requireNonNull(
                definition,
                "definition must not be null"
        );

        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );

        if (state == PolicyVersionState.DRAFT) {
            if (publishedAt != null) {
                throw new IllegalArgumentException(
                        "draft policy version must not have publishedAt"
                );
            }

            if (definitionHash != null) {
                throw new IllegalArgumentException(
                        "draft policy version must not have definitionHash"
                );
            }
        }

        if (state == PolicyVersionState.PUBLISHED) {
            if (publishedAt == null) {
                throw new IllegalArgumentException(
                        "published policy version must have publishedAt"
                );
            }

            if (definitionHash == null) {
                throw new IllegalArgumentException(
                        "published policy version must have definitionHash"
                );
            }
        }

        if (publishedAt != null
                && publishedAt.isBefore(
                        createdAt
                )) {
            throw new IllegalArgumentException(
                    "publishedAt must not be before createdAt"
            );
        }
    }

    public boolean isPublished() {
        return state
                == PolicyVersionState.PUBLISHED;
    }
}