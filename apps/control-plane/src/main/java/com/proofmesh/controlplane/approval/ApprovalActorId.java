package com.proofmesh.controlplane.approval;

import java.util.Objects;

public record ApprovalActorId(
        String value
) {

    public ApprovalActorId {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "value must not be blank"
            );
        }

        if (value.length() > 255) {
            throw new IllegalArgumentException(
                    "value must not exceed 255 characters"
            );
        }
    }
}