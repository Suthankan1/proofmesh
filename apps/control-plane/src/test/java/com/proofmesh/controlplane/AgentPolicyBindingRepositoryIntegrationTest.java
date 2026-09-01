package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingRepository;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentPolicyBindingRepositoryIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "31000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "31000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "32000000-0000-0000-0000-000000000001"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "33000000-0000-0000-0000-000000000001"
            );

    private static final UUID POLICY_VERSION_UUID =
            UUID.fromString(
                    "34000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    POLICY_VERSION_UUID
            );

    private static final UUID ACTIVE_BINDING_ID =
            UUID.fromString(
                    "35000000-0000-0000-0000-000000000001"
            );

    private static final UUID HISTORICAL_BINDING_ID =
            UUID.fromString(
                    "35000000-0000-0000-0000-000000000002"
            );

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T05:00:00Z"
            );

    private static final OffsetDateTime PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T05:30:00Z"
            );

    private static final OffsetDateTime ACTIVATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T06:00:00Z"
            );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AgentPolicyBindingRepository repository;

    @BeforeEach
    void setUp() {
        insertOrganization(
                ORGANIZATION_ID,
                "binding-repository",
                "Binding Repository"
        );

        insertOrganization(
                OTHER_ORGANIZATION_ID,
                "binding-repository-other",
                "Binding Repository Other"
        );

        insertAgent();

        insertPolicy();

        insertPublishedPolicyVersion();
    }

    @Test
    void loadsOpenBindingWithinOwningOrganization() {
        insertOpenBinding(
                ACTIVE_BINDING_ID,
                ACTIVATED_AT
        );

        AgentPolicyBinding binding =
                repository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
                        .orElseThrow();

        assertThat(
                binding.id()
        ).isEqualTo(
                ACTIVE_BINDING_ID
        );

        assertThat(
                binding.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                binding.agentId()
        ).isEqualTo(
                AGENT_ID
        );

        assertThat(
                binding.policyVersionId()
        ).isEqualTo(
                POLICY_VERSION_ID
        );

        assertThat(
                binding.activatedAt()
        ).isEqualTo(
                ACTIVATED_AT.toInstant()
        );

        assertThat(
                binding.deactivatedAt()
        ).isNull();

        assertThat(
                binding.isOpen()
        ).isTrue();
    }

    @Test
    void tenantScopedLookupDoesNotLeakBinding() {
        insertOpenBinding(
                ACTIVE_BINDING_ID,
                ACTIVATED_AT
        );

        Optional<AgentPolicyBinding> result =
                repository
                        .findOpenByOrganizationIdAndAgentId(
                                OTHER_ORGANIZATION_ID,
                                AGENT_ID
                        );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenAgentHasNoOpenBinding() {
        Optional<AgentPolicyBinding> result =
                repository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void ignoresHistoricalDeactivatedBinding() {
        insertHistoricalBinding();

        Optional<AgentPolicyBinding> result =
                repository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void repositoryReturnsFutureOpenBindingWithoutPretendingItIsActive() {
        OffsetDateTime futureActivation =
                OffsetDateTime.parse(
                        "2026-09-02T06:00:00Z"
                );

        insertOpenBinding(
                ACTIVE_BINDING_ID,
                futureActivation
        );

        AgentPolicyBinding binding =
                repository
                        .findOpenByOrganizationIdAndAgentId(
                                ORGANIZATION_ID,
                                AGENT_ID
                        )
                        .orElseThrow();

        assertThat(
                binding.isOpen()
        ).isTrue();

        assertThat(
                binding.isActiveAt(
                        Instant.parse(
                                "2026-09-01T07:00:00Z"
                        )
                )
        ).isFalse();
    }

    private void insertOrganization(
            UUID organizationId,
            String slug,
            String name
    ) {
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
                organizationId,
                slug,
                name,
                "ACTIVE"
        );
    }

    private void insertAgent() {
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
                "Binding Repository Agent",
                "ACTIVE"
        );
    }

    private void insertPolicy() {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.policies (
                    id,
                    organization_id,
                    name,
                    created_at
                )
                VALUES (?, ?, ?, ?)
                """,
                POLICY_ID,
                ORGANIZATION_ID,
                "Binding Repository Policy",
                CREATED_AT
        );
    }

    private void insertPublishedPolicyVersion() {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.policy_versions (
                    id,
                    policy_id,
                    organization_id,
                    version_number,
                    state,
                    definition,
                    definition_hash,
                    created_at,
                    published_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    'PUBLISHED',
                    CAST(? AS jsonb),
                    ?,
                    ?,
                    ?
                )
                """,
                POLICY_VERSION_UUID,
                POLICY_ID,
                ORGANIZATION_ID,
                1,
                """
                {
                  "rules": []
                }
                """,
                "a".repeat(64),
                CREATED_AT,
                PUBLISHED_AT
        );
    }

    private void insertOpenBinding(
            UUID bindingId,
            OffsetDateTime activatedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.agent_policy_bindings (
                    id,
                    organization_id,
                    agent_id,
                    policy_version_id,
                    activated_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                bindingId,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_UUID,
                activatedAt
        );
    }

    private void insertHistoricalBinding() {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.agent_policy_bindings (
                    id,
                    organization_id,
                    agent_id,
                    policy_version_id,
                    activated_at,
                    deactivated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                HISTORICAL_BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_UUID,
                ACTIVATED_AT,
                ACTIVATED_AT.plusHours(1)
        );
    }
}