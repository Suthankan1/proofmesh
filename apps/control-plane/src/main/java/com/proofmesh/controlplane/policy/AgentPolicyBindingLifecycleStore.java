package com.proofmesh.controlplane.policy;

import java.time.Instant;
import java.util.UUID;

public interface AgentPolicyBindingLifecycleStore {

    boolean lockAgent(
            UUID organizationId,
            UUID agentId
    );

    boolean deactivateOpenBinding(
            UUID organizationId,
            UUID agentId,
            UUID bindingId,
            Instant deactivatedAt
    );

    void insert(
            AgentPolicyBinding binding
    );
}