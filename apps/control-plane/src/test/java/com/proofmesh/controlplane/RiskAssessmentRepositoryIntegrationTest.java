package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentInsertResult;
import com.proofmesh.controlplane.risk.RiskAssessmentRepository;
import com.proofmesh.controlplane.risk.RiskLogicVersion;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;

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
import java.util.Optional;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RiskAssessmentRepositoryIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "71000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "71000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "72000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "73000000-0000-0000-0000-000000000001"
            );

    private static final UUID ASSESSMENT_ID =
            UUID.fromString(
                    "74000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_ASSESSMENT_ID =
            UUID.fromString(
                    "74000000-0000-0000-0000-000000000002"
            );

    private static final Instant ASSESSED_AT =
            Instant.parse(
                    "2026-09-01T07:00:00Z"
            );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RiskAssessmentRepository repository;

    @BeforeEach
    void setUp() {
        insertOrganization(
                ORGANIZATION_ID,
                "risk-repository"
        );

        insertOrganization(
                OTHER_ORGANIZATION_ID,
                "risk-repository-other"
        );

        insertAgent();

        insertGovernedAction();
    }

    @Test
    void insertsAndLoadsRiskAssessment() {
        RiskAssessment assessment =
                assessment(
                        ASSESSMENT_ID,
                        65
                );

        RiskAssessmentInsertResult result =
                repository.insertIfAbsent(
                        assessment
                );

        assertThat(result)
                .isInstanceOf(
                        RiskAssessmentInsertResult
                                .Inserted.class
                );

        RiskAssessment loaded =
                repository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                ASSESSMENT_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded
        ).isEqualTo(
                assessment
        );
    }

    @Test
    void loadsAssessmentByGovernedActionWithinTenant() {
        RiskAssessment assessment =
                assessment(
                        ASSESSMENT_ID,
                        65
                );

        repository.insertIfAbsent(
                assessment
        );

        RiskAssessment loaded =
                repository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                GOVERNED_ACTION_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded.id()
        ).isEqualTo(
                ASSESSMENT_ID
        );

        assertThat(
                loaded.governedActionId()
        ).isEqualTo(
                GOVERNED_ACTION_ID
        );
    }

    @Test
    void tenantScopedLookupDoesNotLeakAssessment() {
        repository.insertIfAbsent(
                assessment(
                        ASSESSMENT_ID,
                        65
                )
        );

        Optional<RiskAssessment> result =
                repository
                        .findByOrganizationIdAndGovernedActionId(
                                OTHER_ORGANIZATION_ID,
                                GOVERNED_ACTION_ID
                        );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void secondAssessmentForSameActionReturnsExistingWinner() {
        RiskAssessment winner =
                assessment(
                        ASSESSMENT_ID,
                        65
                );

        RiskAssessment challenger =
                assessment(
                        SECOND_ASSESSMENT_ID,
                        90
                );

        RiskAssessmentInsertResult first =
                repository.insertIfAbsent(
                        winner
                );

        RiskAssessmentInsertResult second =
                repository.insertIfAbsent(
                        challenger
                );

        assertThat(first)
                .isInstanceOf(
                        RiskAssessmentInsertResult
                                .Inserted.class
                );

        assertThat(second)
                .isInstanceOf(
                        RiskAssessmentInsertResult
                                .Existing.class
                );

        RiskAssessment authoritative =
                ((RiskAssessmentInsertResult.Existing)
                        second)
                        .assessment();

        assertThat(
                authoritative.id()
        ).isEqualTo(
                ASSESSMENT_ID
        );

        assertThat(
                authoritative.riskScore()
        ).isEqualTo(
                new RiskScore(
                        65
                )
        );

        assertThat(
                riskAssessmentCount()
        ).isEqualTo(1);
    }

    @Test
    void preservesSignalOrderAndExactProvenance() {
        RiskAssessment assessment =
                assessment(
                        ASSESSMENT_ID,
                        65
                );

        repository.insertIfAbsent(
                assessment
        );

        RiskAssessment loaded =
                repository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                GOVERNED_ACTION_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded.logicVersion()
        ).isEqualTo(
                new RiskLogicVersion(
                        "deterministic-v1"
                )
        );

        assertThat(
                loaded.signals()
        ).containsExactly(
                new RiskSignal(
                        new RiskSignalCode(
                                "BASELINE_TOOL_RISK"
                        ),
                        RiskSeverity.MEDIUM,
                        25,
                        "Tool operation carries baseline runtime risk."
                ),
                new RiskSignal(
                        new RiskSignalCode(
                                "SENSITIVE_OPERATION"
                        ),
                        RiskSeverity.HIGH,
                        40,
                        "Operation has elevated impact."
                )
        );

        assertThat(
                loaded.assessedAt()
        ).isEqualTo(
                ASSESSED_AT
        );
    }

    private RiskAssessment assessment(
            UUID assessmentId,
            int score
    ) {
        return new RiskAssessment(
                assessmentId,
                ORGANIZATION_ID,
                GOVERNED_ACTION_ID,
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                new RiskScore(
                        score
                ),
                List.of(
                        new RiskSignal(
                                new RiskSignalCode(
                                        "BASELINE_TOOL_RISK"
                                ),
                                RiskSeverity.MEDIUM,
                                25,
                                "Tool operation carries baseline runtime risk."
                        ),
                        new RiskSignal(
                                new RiskSignalCode(
                                        "SENSITIVE_OPERATION"
                                ),
                                RiskSeverity.HIGH,
                                40,
                                "Operation has elevated impact."
                        )
                ),
                ASSESSED_AT
        );
    }

    private void insertOrganization(
            UUID organizationId,
            String slug
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
                "Risk Repository Organization",
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
                "Risk Repository Agent",
                "ACTIVE"
        );
    }

    private void insertGovernedAction() {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.governed_actions (
                    id,
                    organization_id,
                    agent_id,
                    idempotency_key,
                    tool_name,
                    operation_name,
                    request_payload,
                    request_payload_hash
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    CAST(? AS jsonb),
                    ?
                )
                """,
                GOVERNED_ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                "risk-repository-action",
                "payments",
                "refund",
                """
                {
                  "paymentId": "pay_123",
                  "amount": 5000
                }
                """,
                "fe033f2b85fbcc6f563d9ecdcb7d04e40e3a8ef239f900db3b0b34bbe3e21ff7"
        );
    }

    private int riskAssessmentCount() {
        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.risk_assessments
                        WHERE organization_id = ?
                          AND governed_action_id = ?
                        """,
                        Integer.class,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID
                );

        return count == null
                ? 0
                : count;
    }
}