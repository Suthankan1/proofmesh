package com.proofmesh.controlplane.agent.internal;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentRepository;
import com.proofmesh.controlplane.agent.AgentResolution;
import com.proofmesh.controlplane.agent.AgentResolver;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
class DefaultAgentResolver
        implements AgentResolver {

    private final AgentRepository agentRepository;

    DefaultAgentResolver(
            AgentRepository agentRepository
    ) {
        this.agentRepository =
                Objects.requireNonNull(
                        agentRepository,
                        "agentRepository must not be null"
                );
    }

    @Override
    public AgentResolution resolve(
            UUID agentId,
            UUID organizationId
    ) {
        Objects.requireNonNull(
                agentId,
                "agentId must not be null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        return agentRepository
                .findByIdAndOrganizationId(
                        agentId,
                        organizationId
                )
                .<AgentResolution>map(
                        this::classify
                )
                .orElseGet(
                        () -> new AgentResolution.Unknown(
                                agentId,
                                organizationId
                        )
                );
    }

    private AgentResolution classify(
            Agent agent
    ) {
        if (!agent.isActive()) {
            return new AgentResolution.Disabled(
                    agent
            );
        }

        return new AgentResolution.Active(
                agent
        );
    }
}