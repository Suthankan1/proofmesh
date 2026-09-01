package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.policy.CanonicalPolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionCanonicalizer;
import com.proofmesh.controlplane.policy.PolicyEffect;
import com.proofmesh.controlplane.policy.PolicyReasonCode;
import com.proofmesh.controlplane.policy.PolicyRiskThreshold;
import com.proofmesh.controlplane.policy.PolicyRule;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyRulePriority;
import com.proofmesh.controlplane.policy.PolicyTarget;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.policy.PolicyVersionIntegrityException;
import com.proofmesh.controlplane.policy.PolicyVersionRepository;
import com.proofmesh.controlplane.policy.PolicyVersionState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PolicyVersionRepositoryIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "91000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "91000000-0000-0000-0000-000000000002"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "92000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "93000000-0000-0000-0000-000000000001"
                    )
            );

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T00:00:00Z"
            );

    private static final OffsetDateTime PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T00:30:00Z"
            );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PolicyVersionRepository repository;

    @Autowired
    PolicyDefinitionCanonicalizer canonicalizer;

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
                "policy-repository-primary",
                "Policy Repository Primary",
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
                "policy-repository-other",
                "Policy Repository Other",
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
                "Refund Governance",
                CREATED_AT
        );
    }

    @Test
    void loadsPublishedPolicyVersionAfterIntegrityVerification() {
        PolicyDefinition definition =
                policyDefinition(
                        80,
                        PolicyEffect.REQUIRE_APPROVAL,
                        "HIGH_RISK_REFUND"
                );

        CanonicalPolicyDefinition canonical =
                canonicalizer.canonicalize(
                        definition
                );

        insertPublished(
                canonical.canonicalJson(),
                canonical.hash().value()
        );

        PolicyVersion loaded =
                repository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                VERSION_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded.state()
        ).isEqualTo(
                PolicyVersionState.PUBLISHED
        );

        assertThat(
                loaded.definition()
        ).isEqualTo(
                definition
        );

        assertThat(
                loaded.definitionHash()
        ).isEqualTo(
                canonical.hash()
        );

        assertThat(
                loaded.isPublished()
        ).isTrue();
    }

    @Test
    void loadsDraftWithoutPublishedDefinitionHash() {
        PolicyDefinition definition =
                policyDefinition(
                        0,
                        PolicyEffect.ALLOW,
                        "STANDARD_REFUND"
                );

        CanonicalPolicyDefinition canonical =
                canonicalizer.canonicalize(
                        definition
                );

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
                VERSION_ID.value(),
                POLICY_ID,
                ORGANIZATION_ID,
                1,
                canonical.canonicalJson(),
                CREATED_AT
        );

        PolicyVersion loaded =
                repository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                VERSION_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded.state()
        ).isEqualTo(
                PolicyVersionState.DRAFT
        );

        assertThat(
                loaded.definitionHash()
        ).isNull();

        assertThat(
                loaded.publishedAt()
        ).isNull();
    }

    @Test
    void tenantScopedLookupDoesNotReturnOtherOrganizationsPolicyVersion() {
        PolicyDefinition definition =
                policyDefinition(
                        0,
                        PolicyEffect.ALLOW,
                        "STANDARD_REFUND"
                );

        CanonicalPolicyDefinition canonical =
                canonicalizer.canonicalize(
                        definition
                );

        insertPublished(
                canonical.canonicalJson(),
                canonical.hash().value()
        );

        Optional<PolicyVersion> result =
                repository
                        .findByOrganizationIdAndId(
                                OTHER_ORGANIZATION_ID,
                                VERSION_ID
                        );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void rejectsPublishedDefinitionWhoseHashDoesNotMatchPersistedContent() {
        PolicyDefinition original =
                policyDefinition(
                        80,
                        PolicyEffect.REQUIRE_APPROVAL,
                        "HIGH_RISK_REFUND"
                );

        PolicyDefinition tampered =
                policyDefinition(
                        0,
                        PolicyEffect.ALLOW,
                        "STANDARD_REFUND"
                );

        CanonicalPolicyDefinition originalCanonical =
                canonicalizer.canonicalize(
                        original
                );

        CanonicalPolicyDefinition tamperedCanonical =
                canonicalizer.canonicalize(
                        tampered
                );

        insertPublished(
                tamperedCanonical.canonicalJson(),
                originalCanonical.hash().value()
        );

        assertThatThrownBy(
                () -> repository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                VERSION_ID
                        )
        )
                .isInstanceOf(
                        PolicyVersionIntegrityException.class
                )
                .hasMessageContaining(
                        "hash does not match"
                );
    }

    @Test
    void rejectsPersistedDefinitionContainingUnsupportedFields() {
        insertPublished(
                """
                {
                  "rules": [],
                  "unexpected": true
                }
                """,
                "a".repeat(64)
        );

        assertThatThrownBy(
                () -> repository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                VERSION_ID
                        )
        )
                .isInstanceOf(
                        PolicyVersionIntegrityException.class
                );
    }

    private PolicyDefinition policyDefinition(
            int minimumRisk,
            PolicyEffect effect,
            String reasonCode
    ) {
        return new PolicyDefinition(
                List.of(
                        new PolicyRule(
                                new PolicyRuleId(
                                        UUID.fromString(
                                                "94000000-0000-0000-0000-000000000001"
                                        )
                                ),
                                new PolicyRulePriority(
                                        100
                                ),
                                new PolicyTarget(
                                        "stripe",
                                        "refund_payment"
                                ),
                                new PolicyRiskThreshold(
                                        minimumRisk
                                ),
                                effect,
                                new PolicyReasonCode(
                                        reasonCode
                                )
                        )
                )
        );
    }

    private void insertPublished(
            String definition,
            String definitionHash
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
                VERSION_ID.value(),
                POLICY_ID,
                ORGANIZATION_ID,
                1,
                definition,
                definitionHash,
                CREATED_AT,
                PUBLISHED_AT
        );
    }
}