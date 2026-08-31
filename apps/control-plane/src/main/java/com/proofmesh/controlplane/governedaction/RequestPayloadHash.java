package com.proofmesh.controlplane.governedaction;

import java.util.Objects;
import java.util.regex.Pattern;

public record RequestPayloadHash(
        String value
) {

    private static final Pattern SHA_256_HEX =
            Pattern.compile(
                    "^[0-9a-f]{64}$"
            );

    public RequestPayloadHash {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        if (!SHA_256_HEX
                .matcher(value)
                .matches()) {
            throw new IllegalArgumentException(
                    "value must be a lowercase SHA-256 hexadecimal string"
            );
        }
    }
}