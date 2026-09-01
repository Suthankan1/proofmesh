package com.proofmesh.controlplane.policy;

import java.time.Instant;
import java.util.UUID;

public interface AgentPolicyBindingSwitcher {

    AgentPolicyBinding switchBinding(
            UUID newBindingId,
            UUID organizationId,
            UUID agentId,
            PolicyVersionId targetPolicyVersionId,
            AgentPolicyBindingExpectation expectation,
            Instant switchedAt
    );
}