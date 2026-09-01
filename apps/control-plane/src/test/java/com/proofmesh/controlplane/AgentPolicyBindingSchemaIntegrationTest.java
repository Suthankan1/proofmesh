package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentPolicyBindingSchemaIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "f1000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "f1000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "f2000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_AGENT_ID =
            UUID.fromString(
                    "f2000000-0000-0000-0000-000000000002"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "f3000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_POLICY_ID =
            UUID.fromString(
                    "f3000000-0000-0000-0000-000000000002"
            );

    private static final UUID POLICY_VERSION_ID =
            UUID.fromString(
                    "f4000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_POLICY_VERSION_ID =
            UUID.fromString(
                    "f4000000-0000-0000-0000-000000000002"
            );

    private static final UUID DRAFT_POLICY_VERSION_ID =
            UUID.fromString(
                    "f4000000-0000-0000-0000-000000000003"
            );

    private static final UUID OTHER_POLICY_VERSION_ID =
            UUID.fromString(
                    "f4000000-0000-0000-0000-000000000004"
            );

    private static final UUID BINDING_ID =
            UUID.fromString(
                    "f5000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_BINDING_ID =
            UUID.fromString(
                    "f5000000-0000-0000-0000-000000000002"
            );

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T05:00:00Z"
            );

    private static final OffsetDateTime PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T05:30:00Z"
            );

    private static final OffsetDateTime SECOND_PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T05:40:00Z"
            );

    private static final OffsetDateTime ACTIVATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T06:00:00Z"
            );

    private static final OffsetDateTime DEACTIVATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T07:00:00Z"
            );

    private static final OffsetDateTime SECOND_ACTIVATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T07:00:00Z"
            );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insertOrganization(
                ORGANIZATION_ID,
                "binding-primary",
                "Binding Primary"
        );

        insertOrganization(
                OTHER_ORGANIZATION_ID,
                "binding-other",
                "Binding Other"
        );

        insertAgent(
                AGENT_ID,
                ORGANIZATION_ID,
                "Primary Binding Agent"
        );

        insertAgent(
                OTHER_AGENT_ID,
                OTHER_ORGANIZATION_ID,
                "Other Binding Agent"
        );

        insertPolicy(
                POLICY_ID,
                ORGANIZATION_ID,
                "Primary Binding Policy"
        );

        insertPolicy(
                OTHER_POLICY_ID,
                OTHER_ORGANIZATION_ID,
                "Other Binding Policy"
        );

        insertPublishedPolicyVersion(
                POLICY_VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                1,
                PUBLISHED_AT
        );

        insertPublishedPolicyVersion(
                SECOND_POLICY_VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                2,
                SECOND_PUBLISHED_AT
        );

        insertDraftPolicyVersion(
                DRAFT_POLICY_VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                3
        );

        insertPublishedPolicyVersion(
                OTHER_POLICY_VERSION_ID,
                OTHER_POLICY_ID,
                OTHER_ORGANIZATION_ID,
                1,
                PUBLISHED_AT
        );
    }

    @Test
    void storesActiveBindingToPublishedPolicyVersion() {
        insertBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT
        );

        UUID policyVersionId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT policy_version_id
                        FROM proofmesh.agent_policy_bindings
                        WHERE id = ?
                        """,
                        UUID.class,
                        BINDING_ID
                );

        OffsetDateTime deactivatedAt =
                jdbcTemplate.queryForObject(
                        """
                        SELECT deactivated_at
                        FROM proofmesh.agent_policy_bindings
                        WHERE id = ?
                        """,
                        OffsetDateTime.class,
                        BINDING_ID
                );

        assertThat(
                policyVersionId
        ).isEqualTo(
                POLICY_VERSION_ID
        );

        assertThat(
                deactivatedAt
        ).isNull();
    }

    @Test
    void rejectsCrossOrganizationAgent() {
        assertThatThrownBy(
                () -> insertBinding(
                        BINDING_ID,
                        ORGANIZATION_ID,
                        OTHER_AGENT_ID,
                        POLICY_VERSION_ID,
                        ACTIVATED_AT
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsCrossOrganizationPolicyVersion() {
        assertThatThrownBy(
                () -> insertBinding(
                        BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        OTHER_POLICY_VERSION_ID,
                        ACTIVATED_AT
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsBindingToDraftPolicyVersion() {
        assertThatThrownBy(
                () -> insertBinding(
                        BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        DRAFT_POLICY_VERSION_ID,
                        ACTIVATED_AT
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "published policy versions"
                );
    }

    @Test
    void rejectsSecondActiveBindingForSameAgent() {
        insertBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT
        );

        assertThatThrownBy(
                () -> insertBinding(
                        SECOND_BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        SECOND_POLICY_VERSION_ID,
                        ACTIVATED_AT.plusMinutes(10)
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void deactivatesActiveBindingWithoutRewritingHistory() {
        insertBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT
        );

        int affectedRows =
                jdbcTemplate.update(
                        """
                        UPDATE proofmesh.agent_policy_bindings
                        SET deactivated_at = ?
                        WHERE id = ?
                        """,
                        DEACTIVATED_AT,
                        BINDING_ID
                );

        assertThat(
                affectedRows
        ).isEqualTo(1);

        UUID policyVersionId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT policy_version_id
                        FROM proofmesh.agent_policy_bindings
                        WHERE id = ?
                        """,
                        UUID.class,
                        BINDING_ID
                );

        OffsetDateTime deactivatedAt =
                jdbcTemplate.queryForObject(
                        """
                        SELECT deactivated_at
                        FROM proofmesh.agent_policy_bindings
                        WHERE id = ?
                        """,
                        OffsetDateTime.class,
                        BINDING_ID
                );

        assertThat(
                policyVersionId
        ).isEqualTo(
                POLICY_VERSION_ID
        );

        assertThat(
                deactivatedAt
        ).isEqualTo(
                DEACTIVATED_AT
        );
    }

    @Test
    void allowsNewBindingAfterPreviousBindingIsDeactivated() {
        insertBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT
        );

        jdbcTemplate.update(
                """
                UPDATE proofmesh.agent_policy_bindings
                SET deactivated_at = ?
                WHERE id = ?
                """,
                DEACTIVATED_AT,
                BINDING_ID
        );

        insertBinding(
                SECOND_BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                SECOND_POLICY_VERSION_ID,
                SECOND_ACTIVATED_AT
        );

        Integer activeCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.agent_policy_bindings
                        WHERE organization_id = ?
                          AND agent_id = ?
                          AND deactivated_at IS NULL
                        """,
                        Integer.class,
                        ORGANIZATION_ID,
                        AGENT_ID
                );

        Integer historyCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.agent_policy_bindings
                        WHERE organization_id = ?
                          AND agent_id = ?
                        """,
                        Integer.class,
                        ORGANIZATION_ID,
                        AGENT_ID
                );

        assertThat(
                activeCount
        ).isEqualTo(1);

        assertThat(
                historyCount
        ).isEqualTo(2);
    }

    @Test
    void rejectsActivationBeforePolicyPublication() {
        assertThatThrownBy(
                () -> insertBinding(
                        BINDING_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        POLICY_VERSION_ID,
                        PUBLISHED_AT.minusSeconds(1)
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "before policy publication"
                );
    }

    @Test
    void rejectsRewritingPolicyVersionOfExistingBinding() {
        insertBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.agent_policy_bindings
                        SET policy_version_id = ?
                        WHERE id = ?
                        """,
                        SECOND_POLICY_VERSION_ID,
                        BINDING_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "immutable"
                );
    }

    @Test
    void rejectsReactivationOfDeactivatedHistoricalBinding() {
        insertBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT
        );

        jdbcTemplate.update(
                """
                UPDATE proofmesh.agent_policy_bindings
                SET deactivated_at = ?
                WHERE id = ?
                """,
                DEACTIVATED_AT,
                BINDING_ID
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.agent_policy_bindings
                        SET deactivated_at = NULL
                        WHERE id = ?
                        """,
                        BINDING_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "deactivated agent policy bindings are immutable"
                );
    }

    @Test
    void rejectsDeletionOfBindingHistory() {
        insertBinding(
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                ACTIVATED_AT
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        DELETE FROM proofmesh.agent_policy_bindings
                        WHERE id = ?
                        """,
                        BINDING_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "cannot be deleted"
                );
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

    private void insertAgent(
            UUID agentId,
            UUID organizationId,
            String name
    ) {
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
                agentId,
                organizationId,
                name,
                "ACTIVE"
        );
    }

    private void insertPolicy(
            UUID policyId,
            UUID organizationId,
            String name
    ) {
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
                policyId,
                organizationId,
                name,
                CREATED_AT
        );
    }

    private void insertPublishedPolicyVersion(
            UUID policyVersionId,
            UUID policyId,
            UUID organizationId,
            int versionNumber,
            OffsetDateTime publishedAt
    ) {
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
                policyVersionId,
                policyId,
                organizationId,
                versionNumber,
                """
                {
                  "rules": []
                }
                """,
                "a".repeat(64),
                CREATED_AT,
                publishedAt
        );
    }

    private void insertDraftPolicyVersion(
            UUID policyVersionId,
            UUID policyId,
            UUID organizationId,
            int versionNumber
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.policy_versions (
                    id,
                    policy_id,
                    organization_id,
                    version_number,
                    state,
                    definition,
                    created_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    'DRAFT',
                    CAST(? AS jsonb),
                    ?
                )
                """,
                policyVersionId,
                policyId,
                organizationId,
                versionNumber,
                """
                {
                  "rules": []
                }
                """,
                CREATED_AT
        );
    }

    private void insertBinding(
            UUID bindingId,
            UUID organizationId,
            UUID agentId,
            UUID policyVersionId,
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
                organizationId,
                agentId,
                policyVersionId,
                activatedAt
        );
    }
}