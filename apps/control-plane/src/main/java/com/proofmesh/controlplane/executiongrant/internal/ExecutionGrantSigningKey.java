package com.proofmesh.controlplane.executiongrant.internal;

import java.security.interfaces.ECPrivateKey;
import java.util.Objects;

final class ExecutionGrantSigningKey {

    private final String keyId;
    private final ECPrivateKey privateKey;

    ExecutionGrantSigningKey(String keyId, ECPrivateKey privateKey) {
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
                privateKey,
                "privateKey must not be null"
        );

        this.keyId = keyId;
        this.privateKey = privateKey;
    }

    String keyId() {
        return keyId;
    }

    ECPrivateKey privateKey() {
        return privateKey;
    }

    @Override
    public String toString() {
        return "ExecutionGrantSigningKey[keyId=" + keyId + ", privateKey=[REDACTED]]";
    }
}
