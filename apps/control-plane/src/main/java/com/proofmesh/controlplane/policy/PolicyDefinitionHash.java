package com.proofmesh.controlplane.policy;

import java.util.Objects;
import java.util.regex.Pattern;

public record PolicyDefinitionHash(
        String value
) {

    private static final Pattern FORMAT =
            Pattern.compile(
                    "^[0-9a-f]{64}$"
            );

    public PolicyDefinitionHash {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        if (!FORMAT
                .matcher(value)
                .matches()) {
            throw new IllegalArgumentException(
                    "policy definition hash must be a lowercase SHA-256 hex value"
            );
        }
    }
}