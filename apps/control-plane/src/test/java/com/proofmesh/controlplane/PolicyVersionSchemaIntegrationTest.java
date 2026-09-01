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
class PolicyVersionSchemaIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "71000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "71000000-0000-0000-0000-000000000002"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "72000000-0000-0000-0000-000000000001"
            );

    private static final UUID VERSION_ID =
            UUID.fromString(
                    "73000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_VERSION_ID =
            UUID.fromString(
                    "73000000-0000-0000-0000-000000000002"
            );

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse(
                    "2026-08-31T10:00:00Z"
            );

    private static final OffsetDateTime PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-08-31T10:30:00Z"
            );

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
                "policy-primary",
                "Policy Primary",
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
                "policy-other",
                "Policy Other",
                "ACTIVE"
        );

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
                "Finance Refund Governance",
                CREATED_AT
        );
    }

    @Test
    void storesDraftPolicyVersion() {
        insertDraft(
                VERSION_ID,
                1
        );

        String state =
                jdbcTemplate.queryForObject(
                        """
                        SELECT state
                        FROM proofmesh.policy_versions
                        WHERE id = ?
                        """,
                        String.class,
                        VERSION_ID
                );

        String definitionType =
                jdbcTemplate.queryForObject(
                        """
                        SELECT pg_typeof(definition)::text
                        FROM proofmesh.policy_versions
                        WHERE id = ?
                        """,
                        String.class,
                        VERSION_ID
                );

        assertThat(state)
                .isEqualTo(
                        "DRAFT"
                );

        assertThat(definitionType)
                .isEqualTo(
                        "jsonb"
                );
    }

    @Test
    void allowsDraftDefinitionToBeEdited() {
        insertDraft(
                VERSION_ID,
                1
        );

        int updated =
                jdbcTemplate.update(
                        """
                        UPDATE proofmesh.policy_versions
                        SET definition =
                            CAST(? AS jsonb)
                        WHERE id = ?
                        """,
                        """
                        {
                          "rules": [
                            {
                              "id": "74000000-0000-0000-0000-000000000001"
                            }
                          ]
                        }
                        """,
                        VERSION_ID
                );

        assertThat(updated)
                .isEqualTo(1);
    }

    @Test
    void publishesPersistedDraft() {
        insertDraft(
                VERSION_ID,
                1
        );

        int updated =
                jdbcTemplate.update(
                        """
                        UPDATE proofmesh.policy_versions
                        SET state = 'PUBLISHED',
                            definition_hash = ?,
                            published_at = ?
                        WHERE id = ?
                        """,
                        "a".repeat(64),
                        PUBLISHED_AT,
                        VERSION_ID
                );

        assertThat(updated)
                .isEqualTo(1);

        String state =
                jdbcTemplate.queryForObject(
                        """
                        SELECT state
                        FROM proofmesh.policy_versions
                        WHERE id = ?
                        """,
                        String.class,
                        VERSION_ID
                );

        assertThat(state)
                .isEqualTo(
                        "PUBLISHED"
                );
    }

    @Test
    void rejectsDefinitionChangeDuringPublication() {
        insertDraft(
                VERSION_ID,
                1
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.policy_versions
                        SET definition =
                                CAST(? AS jsonb),
                            state = 'PUBLISHED',
                            definition_hash = ?,
                            published_at = ?
                        WHERE id = ?
                        """,
                        """
                        {
                          "rules": [
                            {
                              "id": "74000000-0000-0000-0000-000000000001"
                            }
                          ]
                        }
                        """,
                        "a".repeat(64),
                        PUBLISHED_AT,
                        VERSION_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "policy definition must be persisted before publication"
                );
    }

    @Test
    void rejectsModificationOfPublishedVersion() {
        insertPublished(
                VERSION_ID,
                1
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.policy_versions
                        SET definition =
                            CAST(? AS jsonb)
                        WHERE id = ?
                        """,
                        """
                        {
                          "rules": []
                        }
                        """,
                        VERSION_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "published policy versions are immutable"
                );
    }

    @Test
    void rejectsDeletionOfPublishedVersion() {
        insertPublished(
                VERSION_ID,
                1
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        DELETE FROM proofmesh.policy_versions
                        WHERE id = ?
                        """,
                        VERSION_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "published policy versions cannot be deleted"
                );
    }

    @Test
    void rejectsDuplicateVersionNumberForSamePolicy() {
        insertDraft(
                VERSION_ID,
                1
        );

        assertThatThrownBy(
                () -> insertDraft(
                        SECOND_VERSION_ID,
                        1
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsCrossOrganizationPolicyVersion() {
        assertThatThrownBy(
                () -> jdbcTemplate.update(
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
                        VERSION_ID,
                        POLICY_ID,
                        OTHER_ORGANIZATION_ID,
                        1,
                        """
                        {
                          "rules": []
                        }
                        """,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    private void insertDraft(
            UUID versionId,
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
                versionId,
                POLICY_ID,
                ORGANIZATION_ID,
                versionNumber,
                """
                {
                  "rules": []
                }
                """,
                CREATED_AT
        );
    }

    private void insertPublished(
            UUID versionId,
            int versionNumber
    ) {
        insertDraft(
                versionId,
                versionNumber
        );

        jdbcTemplate.update(
                """
                UPDATE proofmesh.policy_versions
                SET state = 'PUBLISHED',
                    definition_hash = ?,
                    published_at = ?
                WHERE id = ?
                """,
                "a".repeat(64),
                PUBLISHED_AT,
                versionId
        );
    }
}