package com.proofmesh.controlplane.executiongrant.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "proofmesh.execution-grant")
record ExecutionGrantPolicyProperties(
        String issuer,
        String audience,
        Duration grantTtl
) {

    ExecutionGrantPolicyProperties {
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
