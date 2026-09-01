package com.proofmesh.controlplane.risk;

import java.util.Objects;
import java.util.regex.Pattern;

public record RiskSignalCode(
        String value
) {

    private static final Pattern VALID_PATTERN =
            Pattern.compile(
                    "^[A-Z][A-Z0-9_]{1,63}$"
            );

    public RiskSignalCode {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        if (!VALID_PATTERN.matcher(
                value
        ).matches()) {
            throw new IllegalArgumentException(
                    "risk signal code must match "
                            + "^[A-Z][A-Z0-9_]{1,63}$"
            );
        }
    }
}