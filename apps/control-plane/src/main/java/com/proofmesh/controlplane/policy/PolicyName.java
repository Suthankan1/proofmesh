package com.proofmesh.controlplane.policy;

import java.util.Objects;

public record PolicyName(
        String value
) {

    private static final int MAX_LENGTH =
            160;

    public PolicyName {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "policy name must not be blank"
            );
        }

        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "policy name must not exceed 160 characters"
            );
        }
    }
}