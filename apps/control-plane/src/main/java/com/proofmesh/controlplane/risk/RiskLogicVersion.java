package com.proofmesh.controlplane.risk;

import java.util.Objects;
import java.util.regex.Pattern;

public record RiskLogicVersion(
        String value
) {

    private static final Pattern VALID_PATTERN =
            Pattern.compile(
                    "^[a-z0-9][a-z0-9._-]{0,63}$"
            );

    public RiskLogicVersion {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        if (!VALID_PATTERN.matcher(
                value
        ).matches()) {
            throw new IllegalArgumentException(
                    "risk logic version has invalid format"
            );
        }
    }
}