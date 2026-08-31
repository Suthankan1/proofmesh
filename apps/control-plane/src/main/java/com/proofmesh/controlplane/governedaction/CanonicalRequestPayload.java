package com.proofmesh.controlplane.governedaction;

import java.util.Objects;

public record CanonicalRequestPayload(
        String canonicalJson,
        RequestPayloadHash hash
) {

    public CanonicalRequestPayload {
        Objects.requireNonNull(
                canonicalJson,
                "canonicalJson must not be null"
        );

        Objects.requireNonNull(
                hash,
                "hash must not be null"
        );

        if (!canonicalJson.startsWith("{")
                || !canonicalJson.endsWith("}")) {
            throw new IllegalArgumentException(
                    "canonicalJson must represent a JSON object"
            );
        }
    }
}