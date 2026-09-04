package com.proofmesh.controlplane.executiongrant.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "proofmesh.execution-grant.signing")
record ExecutionGrantSigningProperties(
        boolean enabled,
        String keyId,
        String privateKeyPath,
        String publicKeyPath
) {

    ExecutionGrantSigningProperties {
        if (enabled) {
            Objects.requireNonNull(
                    keyId,
                    "keyId must not be null"
            );
            if (keyId.isBlank()) {
                throw new IllegalArgumentException(
                        "keyId must not be blank"
                );
            }

            Objects.requireNonNull(
                    privateKeyPath,
                    "privateKeyPath must not be null"
            );
            if (privateKeyPath.isBlank()) {
                throw new IllegalArgumentException(
                        "privateKeyPath must not be blank"
                );
            }

            Objects.requireNonNull(
                    publicKeyPath,
                    "publicKeyPath must not be null"
            );
            if (publicKeyPath.isBlank()) {
                throw new IllegalArgumentException(
                        "publicKeyPath must not be blank"
                );
            }
        }
    }
}
