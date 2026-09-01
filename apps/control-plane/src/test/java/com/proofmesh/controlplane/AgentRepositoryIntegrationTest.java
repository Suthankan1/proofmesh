package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentRepository;
import com.proofmesh.controlplane.agent.AgentStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentRepositoryIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "40000000-0000-0000-0000-000000000001"
            );

    @Autowired
    AgentRepository agentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.organizations (
                    id,
                    slug,
                    name,
                    status
                )
                VALUES (?, ?, ?, ?)
                """,
                ORGANIZATION_ID,
                "primary-organization",
                "Primary Organization",
                "ACTIVE"
        );

        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.organizations (
                    id,
                    slug,
                    name,
                    status
                )
                VALUES (?, ?, ?, ?)
                """,
                OTHER_ORGANIZATION_ID,
                "other-organization",
                "Other Organization",
                "ACTIVE"
        );

        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.agents (
                    id,
                    organization_id,
                    name,
                    status
                )
                VALUES (?, ?, ?, ?)
                """,
                AGENT_ID,
                ORGANIZATION_ID,
                "Finance Refund Agent",
                "ACTIVE"
        );
    }

    @Test
    void findsAgentWithinOwningOrganization() {
        Optional<Agent> result =
                agentRepository
                        .findByIdAndOrganizationId(
                                AGENT_ID,
                                ORGANIZATION_ID
                        );

        assertThat(result)
                .isPresent();

        Agent agent =
                result.orElseThrow();

        assertThat(
                agent.id()
        ).isEqualTo(
                AGENT_ID
        );

        assertThat(
                agent.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                agent.name()
        ).isEqualTo(
                "Finance Refund Agent"
        );

        assertThat(
                agent.status()
        ).isEqualTo(
                AgentStatus.ACTIVE
        );

        assertThat(
                agent.isActive()
        ).isTrue();

        assertThat(
                agent.createdAt()
        ).isNotNull();

        assertThat(
                agent.updatedAt()
        ).isNotNull();
    }

    @Test
    void doesNotFindAgentFromDifferentOrganization() {
        Optional<Agent> result =
                agentRepository
                        .findByIdAndOrganizationId(
                                AGENT_ID,
                                OTHER_ORGANIZATION_ID
                        );

        assertThat(result)
                .isEmpty();
    }
}