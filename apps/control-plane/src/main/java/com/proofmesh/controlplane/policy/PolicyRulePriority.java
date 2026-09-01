package com.proofmesh.controlplane.policy;

public record PolicyRulePriority(
        int value
) implements Comparable<PolicyRulePriority> {

    private static final int MINIMUM =
            1;

    private static final int MAXIMUM =
            10_000;

    public PolicyRulePriority {
        if (value < MINIMUM
                || value > MAXIMUM) {
            throw new IllegalArgumentException(
                    "policy rule priority must be between 1 and 10000"
            );
        }
    }

    @Override
    public int compareTo(
            PolicyRulePriority other
    ) {
        return Integer.compare(
                value,
                other.value
        );
    }
}