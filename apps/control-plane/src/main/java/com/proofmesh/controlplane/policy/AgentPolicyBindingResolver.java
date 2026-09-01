package com.proofmesh.controlplane.policy;

import java.time.Instant;
import java.util.UUID;

public interface AgentPolicyBindingResolver {

    AgentPolicyBindingResolution resolve(
            UUID organizationId,
            UUID agentId,
            Instant asOf
    );
}