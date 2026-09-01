package com.proofmesh.controlplane.policy;

import java.util.Objects;
import java.util.UUID;

public record PolicyVersionId(
        UUID value
) {

    public PolicyVersionId {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );
    }
}