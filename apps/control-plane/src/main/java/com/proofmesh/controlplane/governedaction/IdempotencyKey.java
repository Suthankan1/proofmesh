package com.proofmesh.controlplane.governedaction;

import java.util.Objects;

public record IdempotencyKey(
        String value
) {

    private static final int MAX_LENGTH =
            128;

    public IdempotencyKey {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "value must not be blank"
            );
        }

        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "value must not exceed 128 characters"
            );
        }
    }
}