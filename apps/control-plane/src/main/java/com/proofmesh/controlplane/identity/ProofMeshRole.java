package com.proofmesh.controlplane.identity;

import java.util.Optional;

public enum ProofMeshRole {

    PLATFORM_ADMIN,
    APPROVER,
    SECURITY_OPERATOR,
    VIEWER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<ProofMeshRole> fromTokenRole(String value) {
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(ProofMeshRole.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}