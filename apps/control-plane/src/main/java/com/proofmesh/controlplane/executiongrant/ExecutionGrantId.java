package com.proofmesh.controlplane.executiongrant;

import java.util.Objects;
import java.util.UUID;

public record ExecutionGrantId(
        UUID value
) {

    public ExecutionGrantId {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );
    }
}
