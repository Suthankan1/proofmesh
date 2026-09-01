package com.proofmesh.controlplane.decision;

import java.util.Objects;
import java.util.regex.Pattern;

public record DecisionReasonCode(
        String value
) {

    private static final Pattern FORMAT =
            Pattern.compile(
                    "^[A-Z][A-Z0-9_]{1,63}$"
            );

    public DecisionReasonCode {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        if (!FORMAT
                .matcher(value)
                .matches()) {
            throw new IllegalArgumentException(
                    "decision reason code must use uppercase letters, digits, and underscores"
            );
        }
    }
}