package com.proofmesh.controlplane.agent;

import java.util.UUID;

public interface AgentResolver {

    AgentResolution resolve(
            UUID agentId,
            UUID organizationId
    );
}