package com.proofmesh.controlplane.agent;

import java.util.Objects;
import java.util.UUID;

public sealed interface AgentResolution
        permits AgentResolution.Active,
                AgentResolution.Unknown,
                AgentResolution.Disabled {

    record Active(
            Agent agent
    ) implements AgentResolution {

        public Active {
            Objects.requireNonNull(
                    agent,
                    "agent must not be null"
            );
        }
    }

    record Unknown(
            UUID agentId,
            UUID organizationId
    ) implements AgentResolution {

        public Unknown {
            Objects.requireNonNull(
                    agentId,
                    "agentId must not be null"
            );

            Objects.requireNonNull(
                    organizationId,
                    "organizationId must not be null"
            );
        }
    }

    record Disabled(
            Agent agent
    ) implements AgentResolution {

        public Disabled {
            Objects.requireNonNull(
                    agent,
                    "agent must not be null"
            );
        }
    }
}