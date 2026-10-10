package com.proofmesh.controlplane.agent;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedAgent(UUID organizationId, UUID agentId) {
    public AuthenticatedAgent {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
    }
}
