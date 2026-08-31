package com.proofmesh.controlplane.governedaction;

public interface RequestPayloadCanonicalizer {

    CanonicalRequestPayload canonicalize(
            String json
    );
}