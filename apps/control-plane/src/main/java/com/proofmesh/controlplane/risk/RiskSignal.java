package com.proofmesh.controlplane.risk;

import java.util.Objects;

public record RiskSignal(
        RiskSignalCode code,
        RiskSeverity severity,
        int weight,
        String explanation
) {

    public RiskSignal {
        Objects.requireNonNull(
                code,
                "code must not be null"
        );

        Objects.requireNonNull(
                severity,
                "severity must not be null"
        );

        Objects.requireNonNull(
                explanation,
                "explanation must not be null"
        );

        if (weight < 0
                || weight > 100) {
            throw new IllegalArgumentException(
                    "risk signal weight must be between 0 and 100"
            );
        }

        if (explanation.isBlank()) {
            throw new IllegalArgumentException(
                    "risk signal explanation must not be blank"
            );
        }

        if (explanation.length() > 500) {
            throw new IllegalArgumentException(
                    "risk signal explanation must not exceed 500 characters"
            );
        }
    }
}