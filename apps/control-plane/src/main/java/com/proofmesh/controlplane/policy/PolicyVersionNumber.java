package com.proofmesh.controlplane.policy;

public record PolicyVersionNumber(
        int value
) {

    public PolicyVersionNumber {
        if (value < 1) {
            throw new IllegalArgumentException(
                    "policy version number must be at least 1"
            );
        }
    }

    public PolicyVersionNumber next() {
        return new PolicyVersionNumber(
                value + 1
        );
    }
}