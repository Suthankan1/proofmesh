package com.proofmesh.controlplane.agent;

import java.util.Optional;
import java.util.UUID;

public interface AgentRepository {

    Optional<Agent> findByIdAndOrganizationId(
            UUID agentId,
            UUID organizationId
    );
}