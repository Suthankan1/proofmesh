package com.proofmesh.controlplane.executiongrant;

import java.util.Objects;

public final class SignedExecutionGrant {

    private final String compactToken;

    public SignedExecutionGrant(String compactToken) {
        Objects.requireNonNull(
                compactToken,
                "compactToken must not be null"
        );

        if (compactToken.isBlank()) {
            throw new IllegalArgumentException(
                    "compactToken must not be blank"
            );
        }

        this.compactToken = compactToken;
    }

    public String compactToken() {
        return compactToken;
    }

    @Override
    public String toString() {
        return "SignedExecutionGrant[compactToken=[REDACTED]]";
    }
}
