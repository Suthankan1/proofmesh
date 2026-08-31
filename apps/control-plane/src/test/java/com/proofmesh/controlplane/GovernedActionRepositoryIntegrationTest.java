package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.GovernedActionInsertResult;
import com.proofmesh.controlplane.governedaction.GovernedActionIntegrityException;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadCanonicalizer;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GovernedActionRepositoryIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "d0000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "d0000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "e0000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "f0000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_ACTION_ID =
            UUID.fromString(
                    "f0000000-0000-0000-0000-000000000002"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-30T05:15:00Z"
            );

    @Autowired
    GovernedActionRepository governedActionRepository;

    @Autowired
    RequestPayloadCanonicalizer requestPayloadCanonicalizer;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "DELETE FROM proofmesh.governed_actions"
        );

        jdbcTemplate.update(
                "DELETE FROM proofmesh.agents"
        );

        jdbcTemplate.update(
                "DELETE FROM proofmesh.organization_memberships"
        );

        jdbcTemplate.update(
                "DELETE FROM proofmesh.operator_users"
        );

        jdbcTemplate.update(
                "DELETE FROM proofmesh.organizations"
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
                ORGANIZATION_ID,
                "governed-action-primary",
                "Governed Action Primary",
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
                "governed-action-other",
                "Governed Action Other",
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
    void insertsAndLoadsGovernedActionWithJsonbPayload() {
        CanonicalRequestPayload payload =
                requestPayloadCanonicalizer
                        .canonicalize(
                                """
                                {
                                  "paymentId": "pay_123",
                                  "amount": 5000
                                }
                                """
                        );

        GovernedAction action =
                createAction(
                        ACTION_ID,
                        payload,
                        "request-001"
                );

        GovernedActionInsertResult insertResult =
                governedActionRepository
                        .insertIfAbsent(
                                action
                        );

        assertThat(insertResult)
                .isEqualTo(
                        new GovernedActionInsertResult.Inserted(
                                action
                        )
                );

        Optional<GovernedAction> result =
                governedActionRepository
                        .findByIdAndOrganizationId(
                                ACTION_ID,
                                ORGANIZATION_ID
                        );

        assertThat(result)
                .contains(action);

        String databaseType =
                jdbcTemplate.queryForObject(
                        """
                        SELECT pg_typeof(request_payload)::text
                        FROM proofmesh.governed_actions
                        WHERE id = ?
                        """,
                        String.class,
                        ACTION_ID
                );

        assertThat(databaseType)
                .isEqualTo("jsonb");

        String paymentId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT request_payload ->> 'paymentId'
                        FROM proofmesh.governed_actions
                        WHERE id = ?
                        """,
                        String.class,
                        ACTION_ID
                );

        assertThat(paymentId)
                .isEqualTo("pay_123");

        String storedHash =
                jdbcTemplate.queryForObject(
                        """
                        SELECT request_payload_hash
                        FROM proofmesh.governed_actions
                        WHERE id = ?
                        """,
                        String.class,
                        ACTION_ID
                );

        assertThat(storedHash)
                .isEqualTo(
                        payload.hash().value()
                );
    }

    @Test
    void returnsExistingActionWhenIdempotencyIdentityAlreadyExists() {
        CanonicalRequestPayload payload =
                canonicalPayload();

        GovernedAction firstAction =
                createAction(
                        ACTION_ID,
                        payload,
                        "request-002"
                );

        GovernedAction secondCandidate =
                createAction(
                        SECOND_ACTION_ID,
                        payload,
                        "request-002"
                );

        GovernedActionInsertResult firstResult =
                governedActionRepository
                        .insertIfAbsent(
                                firstAction
                        );

        GovernedActionInsertResult secondResult =
                governedActionRepository
                        .insertIfAbsent(
                                secondCandidate
                        );

        assertThat(firstResult)
                .isEqualTo(
                        new GovernedActionInsertResult.Inserted(
                                firstAction
                        )
                );

        assertThat(secondResult)
                .isEqualTo(
                        new GovernedActionInsertResult.Existing(
                                firstAction
                        )
                );

        Integer rowCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.governed_actions
                        WHERE organization_id = ?
                          AND agent_id = ?
                          AND idempotency_key = ?
                        """,
                        Integer.class,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        "request-002"
                );

        assertThat(rowCount)
                .isEqualTo(1);
    }

    @Test
    void doesNotLoadActionFromDifferentOrganization() {
        GovernedAction action =
                createAction(
                        ACTION_ID,
                        canonicalPayload(),
                        "request-003"
                );

        GovernedActionInsertResult insertResult =
                governedActionRepository
                        .insertIfAbsent(
                                action
                        );

        assertThat(insertResult)
                .isInstanceOf(
                        GovernedActionInsertResult
                                .Inserted.class
                );

        Optional<GovernedAction> result =
                governedActionRepository
                        .findByIdAndOrganizationId(
                                ACTION_ID,
                                OTHER_ORGANIZATION_ID
                        );

        assertThat(result)
                .isEmpty();
    }

    @Test
    void findsActionByScopedIdempotencyKey() {
        IdempotencyKey idempotencyKey =
                new IdempotencyKey(
                        "request-004"
                );

        GovernedAction action =
                new GovernedAction(
                        ACTION_ID,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        idempotencyKey,
                        new ToolName(
                                "stripe"
                        ),
                        new OperationName(
                                "refund_payment"
                        ),
                        canonicalPayload(),
                        CREATED_AT
                );

        GovernedActionInsertResult insertResult =
                governedActionRepository
                        .insertIfAbsent(
                                action
                        );

        assertThat(insertResult)
                .isInstanceOf(
                        GovernedActionInsertResult
                                .Inserted.class
                );

        Optional<GovernedAction> result =
                governedActionRepository
                        .findByOrganizationIdAndAgentIdAndIdempotencyKey(
                                ORGANIZATION_ID,
                                AGENT_ID,
                                idempotencyKey
                        );

        assertThat(result)
                .contains(action);
    }

    @Test
    void failsClosedWhenPersistedPayloadDoesNotMatchStoredHash() {
        GovernedAction action =
                createAction(
                        ACTION_ID,
                        canonicalPayload(),
                        "request-005"
                );

        GovernedActionInsertResult insertResult =
                governedActionRepository
                        .insertIfAbsent(
                                action
                        );

        assertThat(insertResult)
                .isInstanceOf(
                        GovernedActionInsertResult
                                .Inserted.class
                );

        jdbcTemplate.update(
                """
                UPDATE proofmesh.governed_actions
                SET request_payload =
                    CAST(? AS jsonb)
                WHERE id = ?
                """,
                """
                {
                  "paymentId": "pay_123",
                  "amount": 999999
                }
                """,
                ACTION_ID
        );

        assertThatThrownBy(
                () -> governedActionRepository
                        .findByIdAndOrganizationId(
                                ACTION_ID,
                                ORGANIZATION_ID
                        )
        )
                .isInstanceOf(
                        GovernedActionIntegrityException.class
                )
                .hasMessageContaining(
                        "stored hash"
                );
    }

    private CanonicalRequestPayload canonicalPayload() {
        return requestPayloadCanonicalizer
                .canonicalize(
                        """
                        {
                          "paymentId": "pay_123",
                          "amount": 5000
                        }
                        """
                );
    }

    private GovernedAction createAction(
            UUID actionId,
            CanonicalRequestPayload payload,
            String idempotencyKey
    ) {
        return new GovernedAction(
                actionId,
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey(
                        idempotencyKey
                ),
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                payload,
                CREATED_AT
        );
    }

    @Test
void concurrentInsertsProduceSingleIdempotencyWinner()
        throws Exception {

    CanonicalRequestPayload payload =
            canonicalPayload();

    IdempotencyKey idempotencyKey =
            new IdempotencyKey(
                    "request-concurrent-001"
            );

    GovernedAction firstCandidate =
            new GovernedAction(
                    ACTION_ID,
                    ORGANIZATION_ID,
                    AGENT_ID,
                    idempotencyKey,
                    new ToolName(
                            "stripe"
                    ),
                    new OperationName(
                            "refund_payment"
                    ),
                    payload,
                    CREATED_AT
            );

    GovernedAction secondCandidate =
            new GovernedAction(
                    SECOND_ACTION_ID,
                    ORGANIZATION_ID,
                    AGENT_ID,
                    idempotencyKey,
                    new ToolName(
                            "stripe"
                    ),
                    new OperationName(
                            "refund_payment"
                    ),
                    payload,
                    CREATED_AT
            );

    CountDownLatch ready =
            new CountDownLatch(2);

    CountDownLatch start =
            new CountDownLatch(1);

    ExecutorService executor =
            Executors.newFixedThreadPool(2);

    try {
        Future<GovernedActionInsertResult>
        firstFuture =
                executor.submit(
                        () -> {
                            ready.countDown();

                            if (!start.await(
                                    5,
                                    TimeUnit.SECONDS
                            )) {
                                throw new IllegalStateException(
                                        "Timed out waiting to start first concurrent insert"
                                );
                            }

                            return governedActionRepository
                                    .insertIfAbsent(
                                            firstCandidate
                                    );
                        }
                );

        Future<GovernedActionInsertResult>
        secondFuture =
                executor.submit(
                        () -> {
                            ready.countDown();

                            if (!start.await(
                                    5,
                                    TimeUnit.SECONDS
                            )) {
                                throw new IllegalStateException(
                                        "Timed out waiting to start second concurrent insert"
                                );
                            }

                            return governedActionRepository
                                    .insertIfAbsent(
                                            secondCandidate
                                    );
                        }
                );

        assertThat(
                ready.await(
                        5,
                        TimeUnit.SECONDS
                )
        ).isTrue();

        start.countDown();

        GovernedActionInsertResult firstResult =
                firstFuture.get(
                        10,
                        TimeUnit.SECONDS
                );

        GovernedActionInsertResult secondResult =
                secondFuture.get(
                        10,
                        TimeUnit.SECONDS
                );

        List<GovernedActionInsertResult> results =
                List.of(
                        firstResult,
                        secondResult
                );

        long insertedCount =
                results.stream()
                        .filter(
                                GovernedActionInsertResult
                                        .Inserted.class
                                        ::isInstance
                        )
                        .count();

        long existingCount =
                results.stream()
                        .filter(
                                GovernedActionInsertResult
                                        .Existing.class
                                        ::isInstance
                        )
                        .count();

        assertThat(insertedCount)
                .isEqualTo(1);

        assertThat(existingCount)
                .isEqualTo(1);

        GovernedAction insertedAction =
                results.stream()
                        .filter(
                                GovernedActionInsertResult
                                        .Inserted.class
                                        ::isInstance
                        )
                        .map(
                                GovernedActionInsertResult
                                        .Inserted.class
                                        ::cast
                        )
                        .map(
                                GovernedActionInsertResult
                                        .Inserted
                                        ::governedAction
                        )
                        .findFirst()
                        .orElseThrow();

        GovernedAction existingAction =
                results.stream()
                        .filter(
                                GovernedActionInsertResult
                                        .Existing.class
                                        ::isInstance
                        )
                        .map(
                                GovernedActionInsertResult
                                        .Existing.class
                                        ::cast
                        )
                        .map(
                                GovernedActionInsertResult
                                        .Existing
                                        ::governedAction
                        )
                        .findFirst()
                        .orElseThrow();

        assertThat(existingAction)
                .isEqualTo(
                        insertedAction
                );

        Long rowCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.governed_actions
                        WHERE organization_id = ?
                          AND agent_id = ?
                          AND idempotency_key = ?
                        """,
                        Long.class,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        idempotencyKey.value()
                );

        assertThat(rowCount)
                .isEqualTo(1L);

        UUID storedActionId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT id
                        FROM proofmesh.governed_actions
                        WHERE organization_id = ?
                          AND agent_id = ?
                          AND idempotency_key = ?
                        """,
                        UUID.class,
                        ORGANIZATION_ID,
                        AGENT_ID,
                        idempotencyKey.value()
                );

        assertThat(storedActionId)
                .isEqualTo(
                        insertedAction.id()
                );
    } finally {
        executor.shutdownNow();
    }
}
}