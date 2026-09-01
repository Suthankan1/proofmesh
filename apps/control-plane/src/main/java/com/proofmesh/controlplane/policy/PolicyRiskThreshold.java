package com.proofmesh.controlplane.policy;

public record PolicyRiskThreshold(
        int minimum
) {

    private static final int MINIMUM =
            0;

    private static final int MAXIMUM =
            100;

    public PolicyRiskThreshold {
        if (minimum < MINIMUM
                || minimum > MAXIMUM) {
            throw new IllegalArgumentException(
                    "policy minimum risk must be between 0 and 100"
            );
        }
    }

    public boolean matches(
            int riskScore
    ) {
        if (riskScore < MINIMUM
                || riskScore > MAXIMUM) {
            throw new IllegalArgumentException(
                    "risk score must be between 0 and 100"
            );
        }

        return riskScore >= minimum;
    }
}