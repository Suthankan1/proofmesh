package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionInsertResult;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.policy.PolicyRuleId;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GovernanceDecisionRepositoryIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "c1000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "c1000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "c2000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "c3000000-0000-0000-0000-000000000001"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "c4000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "c5000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "c6000000-0000-0000-0000-000000000001"
                    )
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "c7000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_DECISION_ID =
            UUID.fromString(
                    "c7000000-0000-0000-0000-000000000002"
            );

    private static final Instant DECIDED_AT =
            Instant.parse(
                    "2026-09-01T01:00:00Z"
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
    GovernanceDecisionRepository repository;

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
                "decision-repository",
                "Decision Repository",
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
                "decision-repository-other",
                "Decision Repository Other",
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
                "Decision Agent",
                "ACTIVE"
        );

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
                    request_payload_hash,
                    created_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    CAST(? AS jsonb),
                    ?,
                    ?
                )
                """,
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                "decision-repository-action",
                "stripe",
                "refund_payment",
                """
                {
                  "amount": 5000,
                  "paymentId": "pay_123"
                }
                """,
                "a".repeat(64),
                CREATED_AT
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
                "Decision Repository Policy",
                CREATED_AT
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
                POLICY_VERSION_ID.value(),
                POLICY_ID,
                ORGANIZATION_ID,
                1,
                """
                {
                  "rules": [
                    {
                      "id": "c6000000-0000-0000-0000-000000000001",
                      "priority": 100,
                      "target": {
                        "tool": "stripe",
                        "operation": "refund_payment"
                      },
                      "risk": {
                        "minimum": 80
                      },
                      "effect": "REQUIRE_APPROVAL",
                      "reasonCode": "HIGH_RISK_REFUND"
                    }
                  ]
                }
                """,
                "a".repeat(64),
                CREATED_AT,
                PUBLISHED_AT
        );
    }

    @Test
    void insertsAndLoadsMatchedDecision() {
        GovernanceDecision decision =
                matchedDecision(
                        DECISION_ID
                );

        GovernanceDecisionInsertResult result =
                repository.insertIfAbsent(
                        decision
                );

        assertThat(result)
                .isInstanceOf(
                        GovernanceDecisionInsertResult
                                .Inserted.class
                );

        GovernanceDecision loaded =
                repository
                        .findByOrganizationIdAndId(
                                ORGANIZATION_ID,
                                DECISION_ID
                        )
                        .orElseThrow();

        assertThat(loaded)
                .isEqualTo(
                        decision
                );
    }

    @Test
    void preservesOrderedReasonCodes() {
        GovernanceDecision decision =
                new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(95),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                ),
                                new DecisionReasonCode(
                                        "SENSITIVE_PAYMENT_OPERATION"
                                )
                        ),
                        DECIDED_AT
                );

        repository.insertIfAbsent(
                decision
        );

        GovernanceDecision loaded =
                repository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded.reasonCodes()
        ).containsExactly(
                new DecisionReasonCode(
                        "HIGH_RISK_REFUND"
                ),
                new DecisionReasonCode(
                        "SENSITIVE_PAYMENT_OPERATION"
                )
        );
    }

    @Test
    void loadsDefaultDenyWithoutMatchedRule() {
        GovernanceDecision decision =
                new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        DecisionOutcome.DENY,
                        new RiskScore(20),
                        List.of(
                                new DecisionReasonCode(
                                        "NO_APPLICABLE_POLICY_RULE"
                                )
                        ),
                        DECIDED_AT
                );

        repository.insertIfAbsent(
                decision
        );

        GovernanceDecision loaded =
                repository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
                        .orElseThrow();

        assertThat(
                loaded.matchedPolicyRuleId()
        ).isNull();

        assertThat(
                loaded.deniesExecution()
        ).isTrue();
    }

    @Test
    void tenantScopedLookupDoesNotLeakDecision() {
        GovernanceDecision decision =
                matchedDecision(
                        DECISION_ID
                );

        repository.insertIfAbsent(
                decision
        );

        Optional<GovernanceDecision> result =
                repository
                        .findByOrganizationIdAndId(
                                OTHER_ORGANIZATION_ID,
                                DECISION_ID
                        );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void secondDecisionForSameActionReturnsExistingWinner() {
        GovernanceDecision winner =
                matchedDecision(
                        DECISION_ID
                );

        GovernanceDecision second =
                new GovernanceDecision(
                        SECOND_DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(90),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        ),
                        DECIDED_AT.plusSeconds(1)
                );

        GovernanceDecisionInsertResult firstResult =
                repository.insertIfAbsent(
                        winner
                );

        GovernanceDecisionInsertResult secondResult =
                repository.insertIfAbsent(
                        second
                );

        assertThat(firstResult)
                .isInstanceOf(
                        GovernanceDecisionInsertResult
                                .Inserted.class
                );

        assertThat(secondResult)
                .isInstanceOf(
                        GovernanceDecisionInsertResult
                                .Existing.class
                );

        GovernanceDecisionInsertResult.Existing existing =
                (GovernanceDecisionInsertResult.Existing)
                        secondResult;

        assertThat(
                existing.decision()
        ).isEqualTo(
                winner
        );

        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.governance_decisions
                        WHERE organization_id = ?
                          AND governed_action_id = ?
                        """,
                        Integer.class,
                        ORGANIZATION_ID,
                        ACTION_ID
                );

        assertThat(count)
                .isEqualTo(1);
    }

    private GovernanceDecision matchedDecision(
            UUID decisionId
    ) {
        return new GovernanceDecision(
                decisionId,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                DecisionOutcome.REQUIRE_APPROVAL,
                new RiskScore(90),
                List.of(
                        new DecisionReasonCode(
                                "HIGH_RISK_REFUND"
                        )
                ),
                DECIDED_AT
        );
    }
}