package com.proofmesh.controlplane.policy;

import java.util.Objects;
import java.util.UUID;

public sealed interface AgentPolicyBindingResolution
        permits AgentPolicyBindingResolution.ActiveBinding,
                AgentPolicyBindingResolution.NoBinding {

    record ActiveBinding(
            AgentPolicyBinding binding
    ) implements AgentPolicyBindingResolution {

        public ActiveBinding {
            Objects.requireNonNull(
                    binding,
                    "binding must not be null"
            );
        }
    }

    record NoBinding(
            UUID organizationId,
            UUID agentId
    ) implements AgentPolicyBindingResolution {

        public NoBinding {
            Objects.requireNonNull(
                    organizationId,
                    "organizationId must not be null"
            );

            Objects.requireNonNull(
                    agentId,
                    "agentId must not be null"
            );
        }
    }
}