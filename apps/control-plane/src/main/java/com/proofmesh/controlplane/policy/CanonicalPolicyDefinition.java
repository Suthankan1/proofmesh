package com.proofmesh.controlplane.policy;

import java.util.Objects;

public record CanonicalPolicyDefinition(
        String canonicalJson,
        PolicyDefinitionHash hash
) {

    public CanonicalPolicyDefinition {
        Objects.requireNonNull(
                canonicalJson,
                "canonicalJson must not be null"
        );

        Objects.requireNonNull(
                hash,
                "hash must not be null"
        );

        if (canonicalJson.isBlank()) {
            throw new IllegalArgumentException(
                    "canonicalJson must not be blank"
            );
        }

        if (!canonicalJson.startsWith("{")
                || !canonicalJson.endsWith("}")) {
            throw new IllegalArgumentException(
                    "canonical policy definition must be a JSON object"
            );
        }
    }
}