package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionHash;
import com.proofmesh.controlplane.policy.PolicyEffect;
import com.proofmesh.controlplane.policy.PolicyPublisher;
import com.proofmesh.controlplane.policy.PolicyReasonCode;
import com.proofmesh.controlplane.policy.PolicyRiskThreshold;
import com.proofmesh.controlplane.policy.PolicyRule;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyRulePriority;
import com.proofmesh.controlplane.policy.PolicyTarget;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.policy.PolicyVersionIntegrityException;
import com.proofmesh.controlplane.policy.PolicyVersionNumber;
import com.proofmesh.controlplane.policy.PolicyVersionRepository;
import com.proofmesh.controlplane.policy.PolicyVersionState;
import com.proofmesh.controlplane.policy.PolicyVersionWriteConflictException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PolicyVersionLifecycleRepositoryIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "a1000000-0000-0000-0000-000000000001"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "a2000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "a3000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-09-01T00:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-09-01T00:30:00Z"
            );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PolicyVersionRepository repository;

    @Autowired
    PolicyPublisher publisher;

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
                "policy-lifecycle",
                "Policy Lifecycle",
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
                "Refund Lifecycle Policy",
                OffsetDateTime.parse(
                        "2026-09-01T00:00:00Z"
                )
        );
    }

    @Test
    void insertsDraftAndLoadsIt() {
        PolicyVersion draft =
                draft(
                        definition(
                                80,
                                PolicyEffect.REQUIRE_APPROVAL,
                                "HIGH_RISK_REFUND"
                        )
                );

        repository.insertDraft(
                draft
        );

        PolicyVersion loaded =
                repository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                VERSION_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded
        ).isEqualTo(
                draft
        );

        assertThat(
                loaded.definitionHash()
        ).isNull();

        assertThat(
                loaded.publishedAt()
        ).isNull();
    }

    @Test
    void updatesDraftDefinitionWhenExpectedDefinitionStillMatches() {
        PolicyVersion original =
                draft(
                        definition(
                                80,
                                PolicyEffect.REQUIRE_APPROVAL,
                                "HIGH_RISK_REFUND"
                        )
                );

        repository.insertDraft(
                original
        );

        PolicyDefinition replacement =
                definition(
                        0,
                        PolicyEffect.ALLOW,
                        "STANDARD_REFUND"
                );

        PolicyVersion updated =
                repository.updateDraftDefinition(
                        original,
                        replacement
                );

        assertThat(
                updated.definition()
        ).isEqualTo(
                replacement
        );

        PolicyVersion loaded =
                repository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                VERSION_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded.definition()
        ).isEqualTo(
                replacement
        );
    }

    @Test
    void rejectsStaleDraftUpdate() {
        PolicyVersion original =
                draft(
                        definition(
                                80,
                                PolicyEffect.REQUIRE_APPROVAL,
                                "HIGH_RISK_REFUND"
                        )
                );

        repository.insertDraft(
                original
        );

        repository.updateDraftDefinition(
                original,
                definition(
                        60,
                        PolicyEffect.REQUIRE_APPROVAL,
                        "MEDIUM_RISK_REFUND"
                )
        );

        assertThatThrownBy(
                () -> repository
                        .updateDraftDefinition(
                                original,
                                definition(
                                        0,
                                        PolicyEffect.ALLOW,
                                        "STANDARD_REFUND"
                                )
                        )
        )
                .isInstanceOf(
                        PolicyVersionWriteConflictException.class
                )
                .hasMessageContaining(
                        "changed"
                );
    }

    @Test
    void persistsPublicationBoundToExactDraft() {
        PolicyVersion draft =
                draft(
                        definition(
                                80,
                                PolicyEffect.REQUIRE_APPROVAL,
                                "HIGH_RISK_REFUND"
                        )
                );

        repository.insertDraft(
                draft
        );

        PolicyVersion published =
                publisher.publish(
                        draft,
                        PUBLISHED_AT
                );

        repository.persistPublication(
                published
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
                draft.definition()
        );

        assertThat(
                loaded.definitionHash()
        ).isEqualTo(
                published.definitionHash()
        );

        assertThat(
                loaded.publishedAt()
        ).isEqualTo(
                PUBLISHED_AT
        );
    }

    @Test
    void rejectsPublicationWhenPersistedDraftChanged() {
        PolicyVersion original =
                draft(
                        definition(
                                80,
                                PolicyEffect.REQUIRE_APPROVAL,
                                "HIGH_RISK_REFUND"
                        )
                );

        repository.insertDraft(
                original
        );

        repository.updateDraftDefinition(
                original,
                definition(
                        0,
                        PolicyEffect.ALLOW,
                        "STANDARD_REFUND"
                )
        );

        PolicyVersion stalePublication =
                publisher.publish(
                        original,
                        PUBLISHED_AT
                );

        assertThatThrownBy(
                () -> repository
                        .persistPublication(
                                stalePublication
                        )
        )
                .isInstanceOf(
                        PolicyVersionWriteConflictException.class
                )
                .hasMessageContaining(
                        "persisted draft changed"
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
    }

    @Test
    void rejectsFabricatedPublishedDefinitionHash() {
        PolicyVersion draft =
                draft(
                        definition(
                                80,
                                PolicyEffect.REQUIRE_APPROVAL,
                                "HIGH_RISK_REFUND"
                        )
                );

        repository.insertDraft(
                draft
        );

        PolicyVersion fabricated =
                new PolicyVersion(
                        draft.id(),
                        draft.policyId(),
                        draft.organizationId(),
                        draft.versionNumber(),
                        PolicyVersionState.PUBLISHED,
                        draft.definition(),
                        new PolicyDefinitionHash(
                                "a".repeat(64)
                        ),
                        draft.createdAt(),
                        PUBLISHED_AT
                );

        assertThatThrownBy(
                () -> repository
                        .persistPublication(
                                fabricated
                        )
        )
                .isInstanceOf(
                        PolicyVersionIntegrityException.class
                )
                .hasMessageContaining(
                        "does not match"
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
    }

    @Test
    void rejectsSecondPublicationAttempt() {
        PolicyVersion draft =
                draft(
                        definition(
                                80,
                                PolicyEffect.REQUIRE_APPROVAL,
                                "HIGH_RISK_REFUND"
                        )
                );

        repository.insertDraft(
                draft
        );

        PolicyVersion published =
                publisher.publish(
                        draft,
                        PUBLISHED_AT
                );

        repository.persistPublication(
                published
        );

        assertThatThrownBy(
                () -> repository
                        .persistPublication(
                                published
                        )
        )
                .isInstanceOf(
                        PolicyVersionWriteConflictException.class
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
    }

    private PolicyVersion draft(
            PolicyDefinition definition
    ) {
        return new PolicyVersion(
                VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                new PolicyVersionNumber(1),
                PolicyVersionState.DRAFT,
                definition,
                null,
                CREATED_AT,
                null
        );
    }

    private PolicyDefinition definition(
            int minimumRisk,
            PolicyEffect effect,
            String reasonCode
    ) {
        return new PolicyDefinition(
                List.of(
                        new PolicyRule(
                                new PolicyRuleId(
                                        UUID.fromString(
                                                "a4000000-0000-0000-0000-000000000001"
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
}