package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingExpectation;
import com.proofmesh.controlplane.policy.AgentPolicyBindingRepository;
import com.proofmesh.controlplane.policy.AgentPolicyBindingSwitchConflictException;
import com.proofmesh.controlplane.policy.AgentPolicyBindingSwitcher;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class AgentPolicyBindingSwitchIntegrationTest {

    private static final String EMPTY_POLICY_HASH =
            "da506c8a9c8a9f31aa00eaeef23d49764b9ace97158a1a0a7aa628e6d446b0fb";

    private static final OffsetDateTime POLICY_CREATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T04:00:00Z"
            );

    private static final OffsetDateTime V1_PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T04:30:00Z"
            );

    private static final OffsetDateTime V2_PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T04:40:00Z"
            );

    private static final OffsetDateTime V3_PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T04:50:00Z"
            );

    private static final Instant INITIAL_ACTIVATED_AT =
            Instant.parse(
                    "2026-09-01T05:00:00Z"
            );

    private static final Instant SWITCHED_AT =
            Instant.parse(
                    "2026-09-01T06:00:00Z"
            );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AgentPolicyBindingSwitcher switcher;

    @Autowired
    AgentPolicyBindingRepository bindingRepository;

    private UUID organizationId;
    private UUID agentId;
    private UUID policyId;

    private PolicyVersionId policyVersionV1;
    private PolicyVersionId policyVersionV2;
    private PolicyVersionId policyVersionV3;

    @BeforeEach
    void setUp() {
        organizationId =
                UUID.randomUUID();

        agentId =
                UUID.randomUUID();

        policyId =
                UUID.randomUUID();

        policyVersionV1 =
                new PolicyVersionId(
                        UUID.randomUUID()
                );

        policyVersionV2 =
                new PolicyVersionId(
                        UUID.randomUUID()
                );

        policyVersionV3 =
                new PolicyVersionId(
                        UUID.randomUUID()
                );

        insertOrganization();
        insertAgent();
        insertPolicy();

        insertPublishedPolicyVersion(
                policyVersionV1,
                1,
                V1_PUBLISHED_AT
        );

        insertPublishedPolicyVersion(
                policyVersionV2,
                2,
                V2_PUBLISHED_AT
        );

        insertPublishedPolicyVersion(
                policyVersionV3,
                3,
                V3_PUBLISHED_AT
        );
    }

    @Test
    void activatesInitialPolicyWhenNoBindingExists() {
        UUID bindingId =
                UUID.randomUUID();

        AgentPolicyBinding result =
                switcher.switchBinding(
                        bindingId,
                        organizationId,
                        agentId,
                        policyVersionV1,
                        new AgentPolicyBindingExpectation.None(),
                        INITIAL_ACTIVATED_AT
                );

        assertThat(
                result.id()
        ).isEqualTo(
                bindingId
        );

        assertThat(
                result.policyVersionId()
        ).isEqualTo(
                policyVersionV1
        );

        assertThat(
                result.activatedAt()
        ).isEqualTo(
                INITIAL_ACTIVATED_AT
        );

        assertThat(
                result.deactivatedAt()
        ).isNull();

        assertThat(
                activeBindingCount()
        ).isEqualTo(1);

        assertThat(
                totalBindingCount()
        ).isEqualTo(1);

        AgentPolicyBinding persisted =
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                organizationId,
                                agentId
                        )
                        .orElseThrow();

        assertThat(
                persisted.id()
        ).isEqualTo(
                bindingId
        );

        assertThat(
                persisted.policyVersionId()
        ).isEqualTo(
                policyVersionV1
        );
    }

    @Test
    void switchesPolicyAtomicallyAndPreservesPreviousBindingAsHistory() {
        UUID currentBindingId =
                UUID.randomUUID();

        UUID replacementBindingId =
                UUID.randomUUID();

        insertOpenBinding(
                currentBindingId,
                policyVersionV1,
                INITIAL_ACTIVATED_AT
        );

        AgentPolicyBinding replacement =
                switcher.switchBinding(
                        replacementBindingId,
                        organizationId,
                        agentId,
                        policyVersionV2,
                        new AgentPolicyBindingExpectation.Existing(
                                currentBindingId
                        ),
                        SWITCHED_AT
                );

        assertThat(
                replacement.id()
        ).isEqualTo(
                replacementBindingId
        );

        assertThat(
                replacement.policyVersionId()
        ).isEqualTo(
                policyVersionV2
        );

        OffsetDateTime oldBindingDeactivatedAt =
                jdbcTemplate.queryForObject(
                        """
                        SELECT deactivated_at
                        FROM proofmesh.agent_policy_bindings
                        WHERE id = ?
                        """,
                        OffsetDateTime.class,
                        currentBindingId
                );

        assertThat(
                oldBindingDeactivatedAt
        ).isNotNull();

        assertThat(
                oldBindingDeactivatedAt.toInstant()
        ).isEqualTo(
                SWITCHED_AT
        );

        assertThat(
                activeBindingCount()
        ).isEqualTo(1);

        assertThat(
                totalBindingCount()
        ).isEqualTo(2);

        AgentPolicyBinding active =
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                organizationId,
                                agentId
                        )
                        .orElseThrow();

        assertThat(
                active.id()
        ).isEqualTo(
                replacementBindingId
        );

        assertThat(
                active.policyVersionId()
        ).isEqualTo(
                policyVersionV2
        );
    }

    @Test
    void staleExpectedBindingCannotOverwriteNewerAuthoritativeBinding() {
        UUID v1BindingId =
                UUID.randomUUID();

        UUID v2BindingId =
                UUID.randomUUID();

        insertOpenBinding(
                v1BindingId,
                policyVersionV1,
                INITIAL_ACTIVATED_AT
        );

        switcher.switchBinding(
                v2BindingId,
                organizationId,
                agentId,
                policyVersionV2,
                new AgentPolicyBindingExpectation.Existing(
                        v1BindingId
                ),
                SWITCHED_AT
        );

        assertThatThrownBy(
                () -> switcher.switchBinding(
                        UUID.randomUUID(),
                        organizationId,
                        agentId,
                        policyVersionV3,
                        new AgentPolicyBindingExpectation.Existing(
                                v1BindingId
                        ),
                        SWITCHED_AT.plusSeconds(30)
                )
        )
                .isInstanceOf(
                        AgentPolicyBindingSwitchConflictException.class
                )
                .hasMessageContaining(
                        "changed since it was observed"
                );

        assertThat(
                activeBindingCount()
        ).isEqualTo(1);

        assertThat(
                totalBindingCount()
        ).isEqualTo(2);

        AgentPolicyBinding active =
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                organizationId,
                                agentId
                        )
                        .orElseThrow();

        assertThat(
                active.id()
        ).isEqualTo(
                v2BindingId
        );

        assertThat(
                active.policyVersionId()
        ).isEqualTo(
                policyVersionV2
        );
    }

    @Test
    void concurrentSwitchesFromSameObservedBindingProduceOneWinnerAndOneConflict()
            throws Exception {

        UUID currentBindingId =
                UUID.randomUUID();

        insertOpenBinding(
                currentBindingId,
                policyVersionV1,
                INITIAL_ACTIVATED_AT
        );

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<SwitchAttempt> first =
                    executor.submit(
                            switchTask(
                                    UUID.randomUUID(),
                                    policyVersionV2,
                                    currentBindingId,
                                    SWITCHED_AT,
                                    ready,
                                    start
                            )
                    );

            Future<SwitchAttempt> second =
                    executor.submit(
                            switchTask(
                                    UUID.randomUUID(),
                                    policyVersionV3,
                                    currentBindingId,
                                    SWITCHED_AT.plusSeconds(1),
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

            SwitchAttempt firstResult =
                    first.get(
                            10,
                            TimeUnit.SECONDS
                    );

            SwitchAttempt secondResult =
                    second.get(
                            10,
                            TimeUnit.SECONDS
                    );

            List<SwitchAttempt> results =
                    List.of(
                            firstResult,
                            secondResult
                    );

            long successCount =
                    results
                            .stream()
                            .filter(
                                    SwitchAttempt::succeeded
                            )
                            .count();

            long conflictCount =
                    results
                            .stream()
                            .filter(
                                    SwitchAttempt::conflicted
                            )
                            .count();

            assertThat(
                    successCount
            ).isEqualTo(1);

            assertThat(
                    conflictCount
            ).isEqualTo(1);

            assertThat(
                    activeBindingCount()
            ).isEqualTo(1);

            assertThat(
                    totalBindingCount()
            ).isEqualTo(2);

            AgentPolicyBinding successfulBinding =
                    results
                            .stream()
                            .filter(
                                    SwitchAttempt::succeeded
                            )
                            .map(
                                    SwitchAttempt::binding
                            )
                            .findFirst()
                            .orElseThrow();

            AgentPolicyBinding persisted =
                    bindingRepository
                            .findOpenByOrganizationIdAndAgentId(
                                    organizationId,
                                    agentId
                            )
                            .orElseThrow();

            assertThat(
                    persisted.id()
            ).isEqualTo(
                    successfulBinding.id()
            );

            assertThat(
                    persisted.policyVersionId()
            ).isEqualTo(
                    successfulBinding.policyVersionId()
            );

            OffsetDateTime originalDeactivatedAt =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT deactivated_at
                            FROM proofmesh.agent_policy_bindings
                            WHERE id = ?
                            """,
                            OffsetDateTime.class,
                            currentBindingId
                    );

            assertThat(
                    originalDeactivatedAt
            ).isNotNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedReplacementInsertRollsBackDeactivationAndKeepsOldBindingActive() {
        UUID currentBindingId =
                UUID.randomUUID();

        insertOpenBinding(
                currentBindingId,
                policyVersionV1,
                INITIAL_ACTIVATED_AT
        );

        /*
         * Deliberately reuse the existing binding ID.
         *
         * The switcher will:
         *
         * 1. lock the agent,
         * 2. verify the current binding,
         * 3. deactivate it,
         * 4. attempt to INSERT the replacement,
         * 5. hit the primary-key violation.
         *
         * Because switchBinding() is transactional,
         * the earlier deactivation must roll back.
         */
        assertThatThrownBy(
                () -> switcher.switchBinding(
                        currentBindingId,
                        organizationId,
                        agentId,
                        policyVersionV2,
                        new AgentPolicyBindingExpectation.Existing(
                                currentBindingId
                        ),
                        SWITCHED_AT
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );

        assertThat(
                activeBindingCount()
        ).isEqualTo(1);

        assertThat(
                totalBindingCount()
        ).isEqualTo(1);

        AgentPolicyBinding stillActive =
                bindingRepository
                        .findOpenByOrganizationIdAndAgentId(
                                organizationId,
                                agentId
                        )
                        .orElseThrow();

        assertThat(
                stillActive.id()
        ).isEqualTo(
                currentBindingId
        );

        assertThat(
                stillActive.policyVersionId()
        ).isEqualTo(
                policyVersionV1
        );

        OffsetDateTime deactivatedAt =
                jdbcTemplate.queryForObject(
                        """
                        SELECT deactivated_at
                        FROM proofmesh.agent_policy_bindings
                        WHERE id = ?
                        """,
                        OffsetDateTime.class,
                        currentBindingId
                );

        assertThat(
                deactivatedAt
        ).isNull();
    }

    private Callable<SwitchAttempt> switchTask(
            UUID newBindingId,
            PolicyVersionId targetPolicyVersionId,
            UUID expectedBindingId,
            Instant switchedAt,
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
                        "concurrent switch start latch timed out"
                );
            }

            try {
                AgentPolicyBinding binding =
                        switcher.switchBinding(
                                newBindingId,
                                organizationId,
                                agentId,
                                targetPolicyVersionId,
                                new AgentPolicyBindingExpectation.Existing(
                                        expectedBindingId
                                ),
                                switchedAt
                        );

                return SwitchAttempt.success(
                        binding
                );
            } catch (AgentPolicyBindingSwitchConflictException exception) {
                return SwitchAttempt.conflict();
            }
        };
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
                "binding-switch-"
                        + organizationId,
                "Binding Switch Integration",
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
                "Binding Switch Agent",
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
                policyId,
                organizationId,
                "Binding Switch Policy",
                POLICY_CREATED_AT
        );
    }

    private void insertPublishedPolicyVersion(
            PolicyVersionId policyVersionId,
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
                policyVersionId.value(),
                policyId,
                organizationId,
                versionNumber,
                """
                {
                  "rules": []
                }
                """,
                EMPTY_POLICY_HASH,
                POLICY_CREATED_AT,
                publishedAt
        );
    }

    private void insertOpenBinding(
            UUID bindingId,
            PolicyVersionId policyVersionId,
            Instant activatedAt
    ) {
        OffsetDateTime activatedAtUtc =
                OffsetDateTime.ofInstant(
                        activatedAt,
                        ZoneOffset.UTC
                );

        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.agent_policy_bindings (
                    id,
                    organization_id,
                    agent_id,
                    policy_version_id,
                    activated_at,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                bindingId,
                organizationId,
                agentId,
                policyVersionId.value(),
                activatedAtUtc,
                activatedAtUtc
        );
    }

    private int activeBindingCount() {
        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.agent_policy_bindings
                        WHERE organization_id = ?
                          AND agent_id = ?
                          AND deactivated_at IS NULL
                        """,
                        Integer.class,
                        organizationId,
                        agentId
                );

        return count == null
                ? 0
                : count;
    }

    private int totalBindingCount() {
        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.agent_policy_bindings
                        WHERE organization_id = ?
                          AND agent_id = ?
                        """,
                        Integer.class,
                        organizationId,
                        agentId
                );

        return count == null
                ? 0
                : count;
    }

    private record SwitchAttempt(
            AgentPolicyBinding binding,
            boolean conflicted
    ) {

        static SwitchAttempt success(
                AgentPolicyBinding binding
        ) {
            return new SwitchAttempt(
                    binding,
                    false
            );
        }

        static SwitchAttempt conflict() {
            return new SwitchAttempt(
                    null,
                    true
            );
        }

        boolean succeeded() {
            return binding != null;
        }
    }
}