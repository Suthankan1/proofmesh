package com.proofmesh.controlplane.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentRepository;
import com.proofmesh.controlplane.agent.AgentResolution;
import com.proofmesh.controlplane.agent.AgentResolver;
import com.proofmesh.controlplane.agent.AgentStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class DefaultAgentResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "60000000-0000-0000-0000-000000000001"
            );

    private AgentRepository agentRepository;

    private AgentResolver agentResolver;

    @BeforeEach
    void setUp() {
        agentRepository =
                Mockito.mock(
                        AgentRepository.class
                );

        agentResolver =
                new DefaultAgentResolver(
                        agentRepository
                );
    }

    @Test
    void resolvesActiveAgent() {
        Agent agent =
                createAgent(
                        AgentStatus.ACTIVE
                );

        when(
                agentRepository
                        .findByIdAndOrganizationId(
                                AGENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(agent)
        );

        AgentResolution result =
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                );

        assertThat(result)
                .isInstanceOf(
                        AgentResolution.Active.class
                );

        AgentResolution.Active active =
                (AgentResolution.Active) result;

        assertThat(active.agent())
                .isEqualTo(agent);
    }

    @Test
    void resolvesDisabledAgent() {
        Agent agent =
                createAgent(
                        AgentStatus.DISABLED
                );

        when(
                agentRepository
                        .findByIdAndOrganizationId(
                                AGENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(agent)
        );

        AgentResolution result =
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                );

        assertThat(result)
                .isInstanceOf(
                        AgentResolution.Disabled.class
                );

        AgentResolution.Disabled disabled =
                (AgentResolution.Disabled) result;

        assertThat(disabled.agent())
                .isEqualTo(agent);
    }

    @Test
    void resolvesUnknownAgent() {
        when(
                agentRepository
                        .findByIdAndOrganizationId(
                                AGENT_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.empty()
        );

        AgentResolution result =
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                );

        assertThat(result)
                .isInstanceOf(
                        AgentResolution.Unknown.class
                );

        AgentResolution.Unknown unknown =
                (AgentResolution.Unknown) result;

        assertThat(unknown.agentId())
                .isEqualTo(AGENT_ID);

        assertThat(unknown.organizationId())
                .isEqualTo(
                        ORGANIZATION_ID
                );
    }

    private Agent createAgent(
            AgentStatus status
    ) {
        Instant now = Instant.now();

        return new Agent(
                AGENT_ID,
                ORGANIZATION_ID,
                "Finance Refund Agent",
                status,
                now,
                now
        );
    }
}