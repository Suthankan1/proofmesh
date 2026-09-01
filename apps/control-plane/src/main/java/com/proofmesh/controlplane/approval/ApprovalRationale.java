package com.proofmesh.controlplane.approval;

import java.util.Objects;

public record ApprovalRationale(
        String value
) {

    public ApprovalRationale {
        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        String normalized =
                value.strip();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "value must not be blank"
            );
        }

        if (normalized.length() > 1000) {
            throw new IllegalArgumentException(
                    "value must not exceed 1000 characters"
            );
        }

        value = normalized;
    }
}