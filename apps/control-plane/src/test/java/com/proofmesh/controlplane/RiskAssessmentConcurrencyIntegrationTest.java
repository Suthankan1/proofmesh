package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentConflictException;
import com.proofmesh.controlplane.risk.RiskAssessmentRecorder;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RiskAssessmentConcurrencyIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RiskAssessmentRecorder recorder;

    private UUID organizationId;
    private UUID agentId;
    private UUID governedActionId;

    @BeforeEach
    void setUp() {
        organizationId =
                UUID.randomUUID();

        agentId =
                UUID.randomUUID();

        governedActionId =
                UUID.randomUUID();

        insertOrganization();
        insertAgent();
        insertGovernedAction();
    }

    @Test
    void equivalentConcurrentAssessmentsConvergeOnSingleAuthoritativeRow()
            throws Exception {

        RiskAssessment firstProposal =
                equivalentAssessment(
                        UUID.randomUUID(),
                        Instant.parse(
                                "2026-09-01T08:30:00Z"
                        )
                );

        RiskAssessment secondProposal =
                equivalentAssessment(
                        UUID.randomUUID(),
                        Instant.parse(
                                "2026-09-01T08:30:01Z"
                        )
                );

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<RecordAttempt> first =
                    executor.submit(
                            recordTask(
                                    firstProposal,
                                    ready,
                                    start
                            )
                    );

            Future<RecordAttempt> second =
                    executor.submit(
                            recordTask(
                                    secondProposal,
                                    ready,
                                    start
                            )
                    );

            assertThat(
                    ready.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            start.countDown();

            RecordAttempt firstResult =
                    first.get(
                            10,
                            TimeUnit.SECONDS
                    );

            RecordAttempt secondResult =
                    second.get(
                            10,
                            TimeUnit.SECONDS
                    );

            assertThat(
                    firstResult.succeeded()
            ).isTrue();

            assertThat(
                    secondResult.succeeded()
            ).isTrue();

            assertThat(
                    firstResult.conflicted()
            ).isFalse();

            assertThat(
                    secondResult.conflicted()
            ).isFalse();

            assertThat(
                    firstResult.assessment().id()
            ).isEqualTo(
                    secondResult.assessment().id()
            );

            assertThat(
                    firstResult.assessment()
                            .hasSameAssessmentSemanticsAs(
                                    secondResult.assessment()
                            )
            ).isTrue();

            assertThat(
                    riskAssessmentCount()
            ).isEqualTo(1);

            UUID persistedId =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT id
                            FROM proofmesh.risk_assessments
                            WHERE organization_id = ?
                              AND governed_action_id = ?
                            """,
                            UUID.class,
                            organizationId,
                            governedActionId
                    );

            assertThat(
                    persistedId
            ).isEqualTo(
                    firstResult.assessment().id()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void contradictoryConcurrentAssessmentsProduceOneWinnerAndOneConflict()
            throws Exception {

        RiskAssessment firstProposal =
                equivalentAssessment(
                        UUID.randomUUID(),
                        Instant.parse(
                                "2026-09-01T08:40:00Z"
                        )
                );

        RiskAssessment conflictingProposal =
                conflictingAssessment(
                        UUID.randomUUID(),
                        Instant.parse(
                                "2026-09-01T08:40:01Z"
                        )
                );

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<RecordAttempt> first =
                    executor.submit(
                            recordTask(
                                    firstProposal,
                                    ready,
                                    start
                            )
                    );

            Future<RecordAttempt> second =
                    executor.submit(
                            recordTask(
                                    conflictingProposal,
                                    ready,
                                    start
                            )
                    );

            assertThat(
                    ready.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            start.countDown();

            RecordAttempt firstResult =
                    first.get(
                            10,
                            TimeUnit.SECONDS
                    );

            RecordAttempt secondResult =
                    second.get(
                            10,
                            TimeUnit.SECONDS
                    );

            List<RecordAttempt> results =
                    List.of(
                            firstResult,
                            secondResult
                    );

            long successCount =
                    results
                            .stream()
                            .filter(
                                    RecordAttempt::succeeded
                            )
                            .count();

            long conflictCount =
                    results
                            .stream()
                            .filter(
                                    RecordAttempt::conflicted
                            )
                            .count();

            assertThat(
                    successCount
            ).isEqualTo(1);

            assertThat(
                    conflictCount
            ).isEqualTo(1);

            assertThat(
                    riskAssessmentCount()
            ).isEqualTo(1);

            RiskAssessment winner =
                    results
                            .stream()
                            .filter(
                                    RecordAttempt::succeeded
                            )
                            .map(
                                    RecordAttempt::assessment
                            )
                            .findFirst()
                            .orElseThrow();

            UUID persistedId =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT id
                            FROM proofmesh.risk_assessments
                            WHERE organization_id = ?
                              AND governed_action_id = ?
                            """,
                            UUID.class,
                            organizationId,
                            governedActionId
                    );

            assertThat(
                    persistedId
            ).isEqualTo(
                    winner.id()
            );

            Integer persistedScore =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT risk_score
                            FROM proofmesh.risk_assessments
                            WHERE organization_id = ?
                              AND governed_action_id = ?
                            """,
                            Integer.class,
                            organizationId,
                            governedActionId
                    );

            assertThat(
                    persistedScore
            ).isEqualTo(
                    winner.riskScore().value()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<RecordAttempt> recordTask(
            RiskAssessment proposal,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();

            if (!start.await(
                    5,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "concurrent risk recording start latch timed out"
                );
            }

            try {
                RiskAssessment recorded =
                        recorder.record(
                                proposal
                        );

                return RecordAttempt.success(
                        recorded
                );
            } catch (RiskAssessmentConflictException exception) {
                return RecordAttempt.conflict();
            }
        };
    }

    private RiskAssessment equivalentAssessment(
            UUID assessmentId,
            Instant assessedAt
    ) {
        return new RiskAssessment(
                assessmentId,
                organizationId,
                governedActionId,
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                new RiskScore(
                        65
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
                                        "FINANCIAL_IMPACT"
                                ),
                                RiskSeverity.HIGH,
                                40,
                                "Operation has deterministic financial impact."
                        )
                ),
                assessedAt
        );
    }

    private RiskAssessment conflictingAssessment(
            UUID assessmentId,
            Instant assessedAt
    ) {
        return new RiskAssessment(
                assessmentId,
                organizationId,
                governedActionId,
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                new RiskScore(
                        90
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
                                        "CRITICAL_FINANCIAL_IMPACT"
                                ),
                                RiskSeverity.CRITICAL,
                                65,
                                "Operation has critical deterministic financial impact."
                        )
                ),
                assessedAt
        );
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
                "risk-concurrency-"
                        + organizationId,
                "Risk Concurrency Organization",
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
                "Risk Concurrency Agent",
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
                governedActionId,
                organizationId,
                agentId,
                "risk-concurrency-"
                        + governedActionId,
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
                        organizationId,
                        governedActionId
                );

        return count == null
                ? 0
                : count;
    }

    private record RecordAttempt(
            RiskAssessment assessment,
            boolean conflicted
    ) {

        static RecordAttempt success(
                RiskAssessment assessment
        ) {
            return new RecordAttempt(
                    assessment,
                    false
            );
        }

        static RecordAttempt conflict() {
            return new RecordAttempt(
                    null,
                    true
            );
        }

        boolean succeeded() {
            return assessment != null;
        }
    }
}