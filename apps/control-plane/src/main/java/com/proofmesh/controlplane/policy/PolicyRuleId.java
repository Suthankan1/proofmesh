package com.proofmesh.controlplane.policy;

import java.util.Objects;
import java.util.UUID;

public record PolicyRuleId(
        UUID value
) {

    public PolicyRuleId {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );
    }
}