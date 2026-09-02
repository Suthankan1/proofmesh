package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestInsertResult;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;
import org.springframework.dao.InvalidDataAccessApiUsageException;

import com.proofmesh.controlplane.approval.ApprovalRequestResolver;
import com.proofmesh.controlplane.approval.ApprovalResolutionConflictException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApprovalRequestRepositoryIntegrationTest {

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T10:00:00Z"
            );

    private static final OffsetDateTime PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T10:01:00Z"
            );

    private static final OffsetDateTime ASSESSED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T10:02:00Z"
            );

    private static final OffsetDateTime GOVERNANCE_DECIDED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T10:03:00Z"
            );

    private static final Instant REQUESTED_AT =
            Instant.parse(
                    "2026-09-01T10:04:00Z"
            );

    private static final Instant EXPIRES_AT =
            Instant.parse(
                    "2026-09-01T10:19:00Z"
            );

    private static final OffsetDateTime HUMAN_DECIDED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T10:10:00Z"
            );

    private static final OffsetDateTime EXPIRED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T10:20:00Z"
            );

    private static final String REQUEST_PAYLOAD_HASH =
            "a".repeat(64);

    @Autowired
    ApprovalRequestRepository approvalRequestRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApprovalRequestResolver approvalRequestResolver;

    private UUID organizationId;
    private UUID otherOrganizationId;
    private UUID agentId;
    private UUID governedActionId;
    private UUID policyId;
    private UUID policyVersionId;
    private UUID approvalRuleId;
    private UUID denyRuleId;
    private UUID riskAssessmentId;
    private UUID governanceDecisionId;

    @BeforeEach
    void setUp() {
        organizationId =
                UUID.randomUUID();

        otherOrganizationId =
                UUID.randomUUID();

        agentId =
                UUID.randomUUID();

        governedActionId =
                UUID.randomUUID();

        policyId =
                UUID.randomUUID();

        policyVersionId =
                UUID.randomUUID();

        approvalRuleId =
                UUID.randomUUID();

        denyRuleId =
                UUID.randomUUID();

        riskAssessmentId =
                UUID.randomUUID();

        governanceDecisionId =
                UUID.randomUUID();

        insertOrganization(
                organizationId,
                "approval-repo-" + organizationId
        );

        insertOrganization(
                otherOrganizationId,
                "approval-repo-other-"
                        + otherOrganizationId
        );

        insertAgent();

        insertGovernedAction();

        insertPolicy();

        insertPolicyVersion();

        insertRiskAssessment();

        insertGovernanceDecision();
    }

    @Test
    void insertsAndLoadsPendingApprovalRequestById() {
        UUID approvalRequestId =
                UUID.randomUUID();

        ApprovalRequest request =
                pendingRequest(
                        approvalRequestId
                );

        ApprovalRequestInsertResult result =
                approvalRequestRepository
                        .insertIfAbsent(
                                request
                        );

        assertThat(result)
                .isEqualTo(
                        new ApprovalRequestInsertResult.Inserted(
                                request
                        )
                );

        Optional<ApprovalRequest> stored =
                approvalRequestRepository
                        .findByOrganizationIdAndId(
                                organizationId,
                                approvalRequestId
                        );

        assertThat(stored)
                .contains(
                        request
                );
    }

    @Test
    void findsApprovalRequestByGovernanceDecisionId() {
        ApprovalRequest request =
                pendingRequest(
                        UUID.randomUUID()
                );

        approvalRequestRepository
                .insertIfAbsent(
                        request
                );

        Optional<ApprovalRequest> stored =
                approvalRequestRepository
                        .findByOrganizationIdAndGovernanceDecisionId(
                                organizationId,
                                governanceDecisionId
                        );

        assertThat(stored)
                .contains(
                        request
                );
    }

    @Test
    void returnsExistingAuthoritativeRequestForSameGovernanceDecision() {
        ApprovalRequest first =
                pendingRequest(
                        UUID.randomUUID()
                );

        ApprovalRequest competing =
                pendingRequest(
                        UUID.randomUUID()
                );

        ApprovalRequestInsertResult firstResult =
                approvalRequestRepository
                        .insertIfAbsent(
                                first
                        );

        ApprovalRequestInsertResult competingResult =
                approvalRequestRepository
                        .insertIfAbsent(
                                competing
                        );

        assertThat(firstResult)
                .isEqualTo(
                        new ApprovalRequestInsertResult.Inserted(
                                first
                        )
                );

        assertThat(competingResult)
                .isEqualTo(
                        new ApprovalRequestInsertResult.Existing(
                                first
                        )
                );

        Long rowCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM proofmesh.approval_requests
                        WHERE organization_id = ?
                          AND governance_decision_id = ?
                        """,
                        Long.class,
                        organizationId,
                        governanceDecisionId
                );

        assertThat(rowCount)
                .isEqualTo(
                        1L
                );
    }

    @Test
    void doesNotLoadApprovalRequestAcrossOrganizations() {
        ApprovalRequest request =
                pendingRequest(
                        UUID.randomUUID()
                );

        approvalRequestRepository
                .insertIfAbsent(
                        request
                );

        assertThat(
                approvalRequestRepository
                        .findByOrganizationIdAndId(
                                otherOrganizationId,
                                request.id()
                        )
        ).isEmpty();

        assertThat(
                approvalRequestRepository
                        .findByOrganizationIdAndGovernanceDecisionId(
                                otherOrganizationId,
                                governanceDecisionId
                        )
        ).isEmpty();
    }

    @Test
    void rejectsInsertionOfAlreadyApprovedRequest() {
        ApprovalRequest approved =
                pendingRequest(
                        UUID.randomUUID()
                )
                        .approve(
                                new ApprovalActorId(
                                        "operator-subject-001"
                                ),
                                new ApprovalRationale(
                                        "Reviewed and approved."
                                ),
                                Instant.parse(
                                        "2026-09-01T10:10:00Z"
                                )
                        );

        assertThatThrownBy(
                () -> approvalRequestRepository
                        .insertIfAbsent(
                                approved
                        )
        )
                .isInstanceOf(
                        InvalidDataAccessApiUsageException.class
                )
                .hasMessageContaining(
                        "only pending"
                )
                .hasCauseInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void loadsApprovedApprovalRequest() {
        ApprovalRequest pending =
                pendingRequest(
                        UUID.randomUUID()
                );

        approvalRequestRepository
                .insertIfAbsent(
                        pending
                );

        jdbcTemplate.update(
                """
                UPDATE proofmesh.approval_requests
                SET status = 'APPROVED',
                    actor_id = ?,
                    rationale = ?,
                    decided_at = ?
                WHERE id = ?
                """,
                "operator-subject-001",
                "Reviewed the exact request and approved it.",
                HUMAN_DECIDED_AT,
                pending.id()
        );

        ApprovalRequest stored =
                approvalRequestRepository
                        .findByOrganizationIdAndId(
                                organizationId,
                                pending.id()
                        )
                        .orElseThrow();

        assertThat(stored.isApproved())
                .isTrue();

        assertThat(stored.state())
                .isInstanceOf(
                        ApprovalState.Approved.class
                );

        ApprovalState.Approved approved =
                (ApprovalState.Approved)
                        stored.state();

        assertThat(
                approved.actorId()
        ).isEqualTo(
                new ApprovalActorId(
                        "operator-subject-001"
                )
        );

        assertThat(
                approved.decidedAt()
        ).isEqualTo(
                HUMAN_DECIDED_AT.toInstant()
        );
    }

    @Test
    void loadsRejectedApprovalRequest() {
        ApprovalRequest pending =
                pendingRequest(
                        UUID.randomUUID()
                );

        approvalRequestRepository
                .insertIfAbsent(
                        pending
                );

        jdbcTemplate.update(
                """
                UPDATE proofmesh.approval_requests
                SET status = 'REJECTED',
                    actor_id = ?,
                    rationale = ?,
                    decided_at = ?
                WHERE id = ?
                """,
                "operator-subject-002",
                "Reviewed and rejected.",
                HUMAN_DECIDED_AT,
                pending.id()
        );

        ApprovalRequest stored =
                approvalRequestRepository
                        .findByOrganizationIdAndId(
                                organizationId,
                                pending.id()
                        )
                        .orElseThrow();

        assertThat(stored.isRejected())
                .isTrue();

        assertThat(stored.state())
                .isInstanceOf(
                        ApprovalState.Rejected.class
                );
    }

    @Test
    void loadsExpiredApprovalRequest() {
        ApprovalRequest pending =
                pendingRequest(
                        UUID.randomUUID()
                );

        approvalRequestRepository
                .insertIfAbsent(
                        pending
                );

        jdbcTemplate.update(
                """
                UPDATE proofmesh.approval_requests
                SET status = 'EXPIRED',
                    expired_at = ?
                WHERE id = ?
                """,
                EXPIRED_AT,
                pending.id()
        );

        ApprovalRequest stored =
                approvalRequestRepository
                        .findByOrganizationIdAndId(
                                organizationId,
                                pending.id()
                        )
                        .orElseThrow();

        assertThat(stored.isExpired())
                .isTrue();

        assertThat(stored.state())
                .isInstanceOf(
                        ApprovalState.Expired.class
                );

        ApprovalState.Expired expired =
                (ApprovalState.Expired)
                        stored.state();

        assertThat(
                expired.expiredAt()
        ).isEqualTo(
                EXPIRED_AT.toInstant()
        );
    }

    @Test
    void concurrentApproveAndRejectProduceOneAuthoritativeResolution()
            throws Exception {

        ApprovalRequest pending =
                pendingRequest(
                        UUID.randomUUID()
                );

        approvalRequestRepository
                .insertIfAbsent(
                        pending
                );

        ApprovalActorId approvingActor =
                new ApprovalActorId(
                        "operator-approve"
                );

        ApprovalRationale approvalRationale =
                new ApprovalRationale(
                        "Approved after exact request review."
                );

        ApprovalActorId rejectingActor =
                new ApprovalActorId(
                        "operator-reject"
                );

        ApprovalRationale rejectionRationale =
                new ApprovalRationale(
                        "Rejected after exact request review."
                );

        Instant resolvedAt =
                Instant.parse(
                        "2026-09-01T10:10:00Z"
                );

        CountDownLatch ready =
                new CountDownLatch(
                        2
                );

        CountDownLatch start =
                new CountDownLatch(
                        1
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(
                        2
                );

        try {
            Future<Boolean> approveFuture =
                    executor.submit(
                            () -> {
                                ready.countDown();

                                if (!start.await(
                                        5,
                                        TimeUnit.SECONDS
                                )) {
                                    throw new IllegalStateException(
                                            "timed out waiting to start approval"
                                    );
                                }

                                try {
                                    approvalRequestResolver
                                            .approve(
                                                    organizationId,
                                                    pending.id(),
                                                    approvingActor,
                                                    approvalRationale,
                                                    resolvedAt
                                            );

                                    return true;
                                } catch (
                                        ApprovalResolutionConflictException
                                                exception
                                ) {
                                    return false;
                                }
                            }
                    );

            Future<Boolean> rejectFuture =
                    executor.submit(
                            () -> {
                                ready.countDown();

                                if (!start.await(
                                        5,
                                        TimeUnit.SECONDS
                                )) {
                                    throw new IllegalStateException(
                                            "timed out waiting to start rejection"
                                    );
                                }

                                try {
                                    approvalRequestResolver
                                            .reject(
                                                    organizationId,
                                                    pending.id(),
                                                    rejectingActor,
                                                    rejectionRationale,
                                                    resolvedAt
                                            );

                                    return true;
                                } catch (
                                        ApprovalResolutionConflictException
                                                exception
                                ) {
                                    return false;
                                }
                            }
                    );

            assertThat(
                    ready.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();

            start.countDown();

            List<Boolean> results =
                    List.of(
                            approveFuture.get(
                                    10,
                                    TimeUnit.SECONDS
                            ),
                            rejectFuture.get(
                                    10,
                                    TimeUnit.SECONDS
                            )
                    );

            assertThat(
                    results.stream()
                            .filter(
                                    Boolean::booleanValue
                            )
                            .count()
            ).isEqualTo(
                    1
            );

            ApprovalRequest authoritative =
                    approvalRequestRepository
                            .findByOrganizationIdAndId(
                                    organizationId,
                                    pending.id()
                            )
                            .orElseThrow();

            assertThat(
                    authoritative.isApproved()
                            || authoritative.isRejected()
            ).isTrue();

            Long terminalRowCount =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT COUNT(*)
                            FROM proofmesh.approval_requests
                            WHERE id = ?
                            AND organization_id = ?
                            AND status IN (
                                'APPROVED',
                                'REJECTED'
                            )
                            """,
                            Long.class,
                            pending.id(),
                            organizationId
                    );

            assertThat(
                    terminalRowCount
            ).isEqualTo(
                    1L
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private ApprovalRequest pendingRequest(
            UUID approvalRequestId
    ) {
        return new ApprovalRequest(
                approvalRequestId,
                organizationId,
                governedActionId,
                governanceDecisionId,
                agentId,
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                new RequestPayloadHash(
                        REQUEST_PAYLOAD_HASH
                ),
                REQUESTED_AT,
                EXPIRES_AT,
                new ApprovalState.Pending()
        );
    }

    private void insertOrganization(
            UUID id,
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
                id,
                slug,
                "Approval Repository Organization",
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
                "Approval Repository Agent",
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
                governedActionId,
                organizationId,
                agentId,
                "approval-repository-action-"
                        + governedActionId,
                "stripe",
                "refund_payment",
                """
                {
                  "amount": 5000,
                  "paymentId": "pay_approval_repository"
                }
                """,
                REQUEST_PAYLOAD_HASH,
                CREATED_AT
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
                "Approval Repository Policy",
                CREATED_AT
        );
    }

    private void insertPolicyVersion() {
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
                1,
                policyDefinitionJson(),
                "d".repeat(64),
                CREATED_AT,
                PUBLISHED_AT
        );
    }

    private void insertRiskAssessment() {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.risk_assessments (
                    id,
                    organization_id,
                    governed_action_id,
                    logic_version,
                    risk_score,
                    signals,
                    assessed_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    CAST(? AS jsonb),
                    ?
                )
                """,
                riskAssessmentId,
                organizationId,
                governedActionId,
                "approval-repository-v1",
                90,
                """
                [
                  {
                    "code": "HIGH_RISK_OPERATION",
                    "severity": "HIGH",
                    "weight": 90,
                    "explanation": "Authoritative approval repository risk."
                  }
                ]
                """,
                ASSESSED_AT
        );
    }

    private void insertGovernanceDecision() {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.governance_decisions (
                    id,
                    organization_id,
                    governed_action_id,
                    policy_version_id,
                    matched_policy_rule_id,
                    outcome,
                    risk_score,
                    reason_codes,
                    decided_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'REQUIRE_APPROVAL',
                    ?,
                    CAST(? AS jsonb),
                    ?
                )
                """,
                governanceDecisionId,
                organizationId,
                governedActionId,
                policyVersionId,
                approvalRuleId,
                90,
                """
                [
                  "HIGH_RISK_REFUND"
                ]
                """,
                GOVERNANCE_DECIDED_AT
        );
    }

    private String policyDefinitionJson() {
        return """
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
                    },
                    {
                      "id": "%s",
                      "priority": 200,
                      "target": {
                        "tool": "stripe",
                        "operation": "refund_payment"
                      },
                      "risk": {
                        "minimum": 0
                      },
                      "effect": "DENY",
                      "reasonCode": "REFUND_BLOCKED"
                    }
                  ]
                }
                """
                .formatted(
                        approvalRuleId,
                        denyRuleId
                );
    }
}