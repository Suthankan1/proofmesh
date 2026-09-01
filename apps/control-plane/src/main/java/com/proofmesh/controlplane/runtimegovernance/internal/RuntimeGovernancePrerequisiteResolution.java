package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;

import java.util.Objects;

sealed interface RuntimeGovernancePrerequisiteResolution
        permits RuntimeGovernancePrerequisiteResolution.Ready,
                RuntimeGovernancePrerequisiteResolution.Failed {

    record Ready(
            Agent agent,
            AgentPolicyBinding policyBinding
    ) implements RuntimeGovernancePrerequisiteResolution {

        public Ready {
            Objects.requireNonNull(
                    agent,
                    "agent must not be null"
            );

            Objects.requireNonNull(
                    policyBinding,
                    "policyBinding must not be null"
            );

            if (!agent.organizationId()
                    .equals(
                            policyBinding.organizationId()
                    )) {
                throw new IllegalArgumentException(
                        "agent and policy binding organization must match"
                );
            }

            if (!agent.id()
                    .equals(
                            policyBinding.agentId()
                    )) {
                throw new IllegalArgumentException(
                        "agent and policy binding agent identity must match"
                );
            }
        }
    }

    record Failed(
            RuntimeGovernanceFailureReason reason
    ) implements RuntimeGovernancePrerequisiteResolution {

        public Failed {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }
}