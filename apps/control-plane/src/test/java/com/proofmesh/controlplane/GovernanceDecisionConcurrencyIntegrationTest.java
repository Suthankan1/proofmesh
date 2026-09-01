package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecorder;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecordingConflictException;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GovernanceDecisionConcurrencyIntegrationTest {

    private UUID organizationId;
    private UUID agentId;
    private UUID actionId;
    private UUID policyId;

    private PolicyVersionId policyVersionId;
    private PolicyRuleId policyRuleId;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    GovernanceDecisionRecorder recorder;

    @BeforeEach
    void setUp() {
        organizationId =
                UUID.randomUUID();

        agentId =
                UUID.randomUUID();

        actionId =
                UUID.randomUUID();

        policyId =
                UUID.randomUUID();

        policyVersionId =
                new PolicyVersionId(
                        UUID.randomUUID()
                );

        policyRuleId =
                new PolicyRuleId(
                        UUID.randomUUID()
                );

        insertOrganization();
        insertAgent();
        insertGovernedAction();
        insertPolicy();
        insertPublishedPolicyVersion();
    }

    @Test
    void equivalentConcurrentEvaluationsConvergeOnSingleAuthoritativeDecision()
            throws Exception {

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        PolicyEvaluationResult evaluation =
                approvalEvaluation(
                        90
                );

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<GovernanceDecision> first =
                    executor.submit(
                            recordingTask(
                                    UUID.randomUUID(),
                                    evaluation,
                                    Instant.parse(
                                            "2026-09-01T06:00:00Z"
                                    ),
                                    ready,
                                    start
                            )
                    );

            Future<GovernanceDecision> second =
                    executor.submit(
                            recordingTask(
                                    UUID.randomUUID(),
                                    evaluation,
                                    Instant.parse(
                                            "2026-09-01T06:00:01Z"
                                    ),
                                    ready,
                                    start
                            )
                    );

            ready.await();
            start.countDown();

            GovernanceDecision firstResult =
                    first.get();

            GovernanceDecision secondResult =
                    second.get();

            assertThat(
                    firstResult
            ).isEqualTo(
                    secondResult
            );

            assertThat(
                    firstResult.hasSameDecisionSemanticsAs(
                            secondResult
                    )
            ).isTrue();

            assertThat(
                    countDecisionsForAction()
            ).isEqualTo(
                    1
            );

            UUID persistedDecisionId =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT id
                            FROM proofmesh.governance_decisions
                            WHERE organization_id = ?
                              AND governed_action_id = ?
                            """,
                            UUID.class,
                            organizationId,
                            actionId
                    );

            assertThat(
                    persistedDecisionId
            ).isEqualTo(
                    firstResult.id()
            );
        }
    }

    @Test
    void conflictingConcurrentEvaluationsProduceOneWinnerAndOneFailClosedConflict()
            throws Exception {

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        PolicyEvaluationResult firstEvaluation =
                approvalEvaluation(
                        90
                );

        PolicyEvaluationResult secondEvaluation =
                approvalEvaluation(
                        80
                );

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<RecordingAttempt> first =
                    executor.submit(
                            observingTask(
                                    UUID.randomUUID(),
                                    firstEvaluation,
                                    Instant.parse(
                                            "2026-09-01T06:10:00Z"
                                    ),
                                    ready,
                                    start
                            )
                    );

            Future<RecordingAttempt> second =
                    executor.submit(
                            observingTask(
                                    UUID.randomUUID(),
                                    secondEvaluation,
                                    Instant.parse(
                                            "2026-09-01T06:10:01Z"
                                    ),
                                    ready,
                                    start
                            )
                    );

            ready.await();
            start.countDown();

            RecordingAttempt firstResult =
                    first.get();

            RecordingAttempt secondResult =
                    second.get();

            long successCount =
                    List.of(
                                    firstResult,
                                    secondResult
                            )
                            .stream()
                            .filter(
                                    RecordingAttempt::succeeded
                            )
                            .count();

            long conflictCount =
                    List.of(
                                    firstResult,
                                    secondResult
                            )
                            .stream()
                            .filter(
                                    RecordingAttempt::conflicted
                            )
                            .count();

            assertThat(
                    successCount
            ).isEqualTo(
                    1
            );

            assertThat(
                    conflictCount
            ).isEqualTo(
                    1
            );

            assertThat(
                    countDecisionsForAction()
            ).isEqualTo(
                    1
            );

            GovernanceDecision winner =
                    List.of(
                                    firstResult,
                                    secondResult
                            )
                            .stream()
                            .filter(
                                    RecordingAttempt::succeeded
                            )
                            .map(
                                    RecordingAttempt::decision
                            )
                            .findFirst()
                            .orElseThrow();

            Integer persistedRiskScore =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT risk_score
                            FROM proofmesh.governance_decisions
                            WHERE organization_id = ?
                              AND governed_action_id = ?
                            """,
                            Integer.class,
                            organizationId,
                            actionId
                    );

            assertThat(
                    persistedRiskScore
            ).isEqualTo(
                    winner.riskScore()
                            .value()
            );
        }
    }

    private Callable<GovernanceDecision> recordingTask(
            UUID decisionId,
            PolicyEvaluationResult evaluation,
            Instant decidedAt,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();

            return recorder
                    .recordAuthoritativeDecision(
                            decisionId,
                            organizationId,
                            actionId,
                            evaluation,
                            decidedAt
                    );
        };
    }

    private Callable<RecordingAttempt> observingTask(
            UUID decisionId,
            PolicyEvaluationResult evaluation,
            Instant decidedAt,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();

            try {
                GovernanceDecision decision =
                        recorder
                                .recordAuthoritativeDecision(
                                        decisionId,
                                        organizationId,
                                        actionId,
                                        evaluation,
                                        decidedAt
                                );

                return RecordingAttempt.success(
                        decision
                );
            } catch (GovernanceDecisionRecordingConflictException exception) {
                return RecordingAttempt.conflict();
            }
        };
    }

    private PolicyEvaluationResult approvalEvaluation(
            int riskScore
    ) {
        return new PolicyEvaluationResult.Matched(
                policyVersionId,
                policyRuleId,
                DecisionOutcome.REQUIRE_APPROVAL,
                new RiskScore(
                        riskScore
                ),
                List.of(
                        new DecisionReasonCode(
                                "HIGH_RISK_REFUND"
                        )
                )
        );
    }

    private int countDecisionsForAction() {
        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.governance_decisions
                        WHERE organization_id = ?
                          AND governed_action_id = ?
                        """,
                        Integer.class,
                        organizationId,
                        actionId
                );

        return count == null
                ? 0
                : count;
    }

    private void insertOrganization() {
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
                "decision-concurrency-"
                        + organizationId,
                "Decision Concurrency",
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
                agentId,
                organizationId,
                "Concurrent Decision Agent",
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
                actionId,
                organizationId,
                agentId,
                "decision-concurrency-"
                        + actionId,
                "stripe",
                "refund_payment",
                """
                {
                  "amount": 5000,
                  "paymentId": "pay_concurrency"
                }
                """,
                "a".repeat(64),
                OffsetDateTime.parse(
                        "2026-09-01T05:00:00Z"
                )
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
                policyId,
                organizationId,
                "Concurrent Decision Policy",
                OffsetDateTime.parse(
                        "2026-09-01T05:00:00Z"
                )
        );
    }

    private void insertPublishedPolicyVersion() {
        String definition =
                """
                {
                  "rules": [
                    {
                      "id": "%s",
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
                """
                        .formatted(
                                policyRuleId.value()
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
                policyVersionId.value(),
                policyId,
                organizationId,
                1,
                definition,
                "a".repeat(64),
                OffsetDateTime.parse(
                        "2026-09-01T05:00:00Z"
                ),
                OffsetDateTime.parse(
                        "2026-09-01T05:30:00Z"
                )
        );
    }

    private record RecordingAttempt(
            GovernanceDecision decision,
            boolean conflicted
    ) {

        static RecordingAttempt success(
                GovernanceDecision decision
        ) {
            return new RecordingAttempt(
                    decision,
                    false
            );
        }

        static RecordingAttempt conflict() {
            return new RecordingAttempt(
                    null,
                    true
            );
        }

        boolean succeeded() {
            return decision != null;
        }
    }
}