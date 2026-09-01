package com.proofmesh.controlplane.policy;

import java.util.Optional;
import java.util.UUID;

public interface AgentPolicyBindingRepository {

    Optional<AgentPolicyBinding>
            findOpenByOrganizationIdAndAgentId(
                    UUID organizationId,
                    UUID agentId
            );
}