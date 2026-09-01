package com.proofmesh.controlplane.decision;

public record RiskScore(
        int value
) {

    private static final int MINIMUM =
            0;

    private static final int MAXIMUM =
            100;

    public RiskScore {
        if (value < MINIMUM
                || value > MAXIMUM) {
            throw new IllegalArgumentException(
                    "risk score must be between 0 and 100"
            );
        }
    }
}