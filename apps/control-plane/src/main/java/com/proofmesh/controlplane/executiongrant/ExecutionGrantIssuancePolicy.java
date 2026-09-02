package com.proofmesh.controlplane.executiongrant;

import java.time.Duration;
import java.util.Objects;

public record ExecutionGrantIssuancePolicy(
        String issuer,
        String audience,
        Duration grantTtl
) {

    public ExecutionGrantIssuancePolicy {
        Objects.requireNonNull(
                issuer,
                "issuer must not be null"
        );

        Objects.requireNonNull(
                audience,
                "audience must not be null"
        );

        Objects.requireNonNull(
                grantTtl,
                "grantTtl must not be null"
        );

        if (issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "issuer must not be blank"
            );
        }

        if (audience.isBlank()) {
            throw new IllegalArgumentException(
                    "audience must not be blank"
            );
        }

        if (grantTtl.isNegative()
                || grantTtl.isZero()) {
            throw new IllegalArgumentException(
                    "grantTtl must be positive"
            );
        }
    }
}
