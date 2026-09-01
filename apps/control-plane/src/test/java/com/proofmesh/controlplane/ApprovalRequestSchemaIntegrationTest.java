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
class ApprovalRequestSchemaIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "f1000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "f1000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "f2000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_AGENT_ID =
            UUID.fromString(
                    "f2000000-0000-0000-0000-000000000002"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "f3000000-0000-0000-0000-000000000001"
            );

    private static final UUID DENY_ACTION_ID =
            UUID.fromString(
                    "f3000000-0000-0000-0000-000000000002"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "f4000000-0000-0000-0000-000000000001"
            );

    private static final UUID POLICY_VERSION_ID =
            UUID.fromString(
                    "f5000000-0000-0000-0000-000000000001"
            );

    private static final UUID APPROVAL_POLICY_RULE_ID =
            UUID.fromString(
                    "f6000000-0000-0000-0000-000000000001"
            );

    private static final UUID DENY_POLICY_RULE_ID =
            UUID.fromString(
                    "f6000000-0000-0000-0000-000000000002"
            );

    private static final UUID RISK_ASSESSMENT_ID =
            UUID.fromString(
                    "f6500000-0000-0000-0000-000000000001"
            );

    private static final UUID DENY_RISK_ASSESSMENT_ID =
            UUID.fromString(
                    "f6500000-0000-0000-0000-000000000002"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "f7000000-0000-0000-0000-000000000001"
            );

    private static final UUID DENY_DECISION_ID =
            UUID.fromString(
                    "f7000000-0000-0000-0000-000000000002"
            );

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString(
                    "f8000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_APPROVAL_REQUEST_ID =
            UUID.fromString(
                    "f8000000-0000-0000-0000-000000000002"
            );

    private static final String REQUEST_PAYLOAD_HASH =
            "a".repeat(64);

    private static final String DENY_REQUEST_PAYLOAD_HASH =
            "b".repeat(64);

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

    private static final OffsetDateTime DECIDED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T10:03:00Z"
            );

    private static final OffsetDateTime REQUESTED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T10:04:00Z"
            );

    private static final OffsetDateTime EXPIRES_AT =
            OffsetDateTime.parse(
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

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insertOrganization(
                ORGANIZATION_ID,
                "approval-primary",
                "Approval Primary"
        );

        insertOrganization(
                OTHER_ORGANIZATION_ID,
                "approval-other",
                "Approval Other"
        );

        insertAgent(
                AGENT_ID,
                ORGANIZATION_ID,
                "Approval Agent"
        );

        insertAgent(
                SECOND_AGENT_ID,
                ORGANIZATION_ID,
                "Different Approval Agent"
        );

        insertGovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                "approval-action-primary",
                REQUEST_PAYLOAD_HASH
        );

        insertGovernedAction(
                DENY_ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                "approval-action-deny",
                DENY_REQUEST_PAYLOAD_HASH
        );

        insertPolicy();

        insertPublishedPolicyVersion();

        insertRiskAssessment(
                RISK_ASSESSMENT_ID,
                ACTION_ID
        );

        insertRiskAssessment(
                DENY_RISK_ASSESSMENT_ID,
                DENY_ACTION_ID
        );

        insertDecision(
                DECISION_ID,
                ACTION_ID,
                APPROVAL_POLICY_RULE_ID,
                "REQUIRE_APPROVAL",
                "HIGH_RISK_REFUND"
        );

        insertDecision(
                DENY_DECISION_ID,
                DENY_ACTION_ID,
                DENY_POLICY_RULE_ID,
                "DENY",
                "REFUND_BLOCKED"
        );
    }

    @Test
    void storesPendingApprovalBoundToAuthoritativeDecisionAndAction() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
        );

        String status =
                jdbcTemplate.queryForObject(
                        """
                        SELECT status
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        String.class,
                        APPROVAL_REQUEST_ID
                );

        UUID decisionId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT governance_decision_id
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        UUID.class,
                        APPROVAL_REQUEST_ID
                );

        UUID agentId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT agent_id
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        UUID.class,
                        APPROVAL_REQUEST_ID
                );

        String payloadHash =
                jdbcTemplate.queryForObject(
                        """
                        SELECT request_payload_hash
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        String.class,
                        APPROVAL_REQUEST_ID
                );

        assertThat(status)
                .isEqualTo(
                        "PENDING"
                );

        assertThat(decisionId)
                .isEqualTo(
                        DECISION_ID
                );

        assertThat(agentId)
                .isEqualTo(
                        AGENT_ID
                );

        assertThat(payloadHash)
                .isEqualTo(
                        REQUEST_PAYLOAD_HASH
                );
    }

    @Test
    void rejectsApprovalForNonRequireApprovalDecision() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        ORGANIZATION_ID,
                        DENY_ACTION_ID,
                        DENY_DECISION_ID,
                        AGENT_ID,
                        "stripe",
                        "refund_payment",
                        DENY_REQUEST_PAYLOAD_HASH,
                        REQUESTED_AT,
                        EXPIRES_AT,
                        "PENDING",
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "REQUIRE_APPROVAL governance decision"
                );
    }

    @Test
    void rejectsApprovalForDifferentActionThanGovernanceDecision() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        ORGANIZATION_ID,
                        DENY_ACTION_ID,
                        DECISION_ID,
                        AGENT_ID,
                        "stripe",
                        "refund_payment",
                        DENY_REQUEST_PAYLOAD_HASH,
                        REQUESTED_AT,
                        EXPIRES_AT,
                        "PENDING",
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "governed action from its governance decision"
                );
    }

    @Test
    void rejectsApprovalBoundToDifferentAgent() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DECISION_ID,
                        SECOND_AGENT_ID,
                        "stripe",
                        "refund_payment",
                        REQUEST_PAYLOAD_HASH,
                        REQUESTED_AT,
                        EXPIRES_AT,
                        "PENDING",
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "binding does not match"
                );
    }

    @Test
    void rejectsApprovalBoundToDifferentTool() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DECISION_ID,
                        AGENT_ID,
                        "github",
                        "refund_payment",
                        REQUEST_PAYLOAD_HASH,
                        REQUESTED_AT,
                        EXPIRES_AT,
                        "PENDING",
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "binding does not match"
                );
    }

    @Test
    void rejectsApprovalBoundToDifferentOperation() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DECISION_ID,
                        AGENT_ID,
                        "stripe",
                        "capture_payment",
                        REQUEST_PAYLOAD_HASH,
                        REQUESTED_AT,
                        EXPIRES_AT,
                        "PENDING",
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "binding does not match"
                );
    }

    @Test
    void rejectsApprovalBoundToDifferentPayloadHash() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DECISION_ID,
                        AGENT_ID,
                        "stripe",
                        "refund_payment",
                        "c".repeat(64),
                        REQUESTED_AT,
                        EXPIRES_AT,
                        "PENDING",
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "binding does not match"
                );
    }

    @Test
    void rejectsApprovalRequestThatPredatesGovernanceDecision() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DECISION_ID,
                        AGENT_ID,
                        "stripe",
                        "refund_payment",
                        REQUEST_PAYLOAD_HASH,
                        DECIDED_AT.minusSeconds(
                                1
                        ),
                        EXPIRES_AT,
                        "PENDING",
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "cannot predate its governance decision"
                );
    }

    @Test
    void rejectsSecondApprovalForSameGovernanceDecision() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
        );

        assertThatThrownBy(
                () -> insertPendingApproval(
                        SECOND_APPROVAL_REQUEST_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsCrossOrganizationGovernanceDecision() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        OTHER_ORGANIZATION_ID,
                        ACTION_ID,
                        DECISION_ID,
                        AGENT_ID,
                        "stripe",
                        "refund_payment",
                        REQUEST_PAYLOAD_HASH,
                        REQUESTED_AT,
                        EXPIRES_AT,
                        "PENDING",
                        null,
                        null,
                        null,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsDirectApprovedInsertion() {
        assertThatThrownBy(
                () -> insertApproval(
                        APPROVAL_REQUEST_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DECISION_ID,
                        AGENT_ID,
                        "stripe",
                        "refund_payment",
                        REQUEST_PAYLOAD_HASH,
                        REQUESTED_AT,
                        EXPIRES_AT,
                        "APPROVED",
                        "operator-subject-001",
                        "Reviewed and approved.",
                        HUMAN_DECIDED_AT,
                        null
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "must be created pending"
                );
    }

    @Test
    void transitionsPendingApprovalToApproved() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
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
                "Reviewed the exact refund request and approved it.",
                HUMAN_DECIDED_AT,
                APPROVAL_REQUEST_ID
        );

        String status =
                jdbcTemplate.queryForObject(
                        """
                        SELECT status
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        String.class,
                        APPROVAL_REQUEST_ID
                );

        String actorId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT actor_id
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        String.class,
                        APPROVAL_REQUEST_ID
                );

        OffsetDateTime decidedAt =
                jdbcTemplate.queryForObject(
                        """
                        SELECT decided_at
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        OffsetDateTime.class,
                        APPROVAL_REQUEST_ID
                );

        assertThat(status)
                .isEqualTo(
                        "APPROVED"
                );

        assertThat(actorId)
                .isEqualTo(
                        "operator-subject-001"
                );

        assertThat(decidedAt)
                .isEqualTo(
                        HUMAN_DECIDED_AT
                );
    }

    @Test
    void transitionsPendingApprovalToRejected() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
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
                "The refund context does not justify this operation.",
                HUMAN_DECIDED_AT,
                APPROVAL_REQUEST_ID
        );

        String status =
                jdbcTemplate.queryForObject(
                        """
                        SELECT status
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        String.class,
                        APPROVAL_REQUEST_ID
                );

        assertThat(status)
                .isEqualTo(
                        "REJECTED"
                );
    }

    @Test
    void transitionsPendingApprovalToExpired() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
        );

        jdbcTemplate.update(
                """
                UPDATE proofmesh.approval_requests
                SET status = 'EXPIRED',
                    expired_at = ?
                WHERE id = ?
                """,
                EXPIRED_AT,
                APPROVAL_REQUEST_ID
        );

        String status =
                jdbcTemplate.queryForObject(
                        """
                        SELECT status
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        String.class,
                        APPROVAL_REQUEST_ID
                );

        OffsetDateTime expiredAt =
                jdbcTemplate.queryForObject(
                        """
                        SELECT expired_at
                        FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        OffsetDateTime.class,
                        APPROVAL_REQUEST_ID
                );

        assertThat(status)
                .isEqualTo(
                        "EXPIRED"
                );

        assertThat(expiredAt)
                .isEqualTo(
                        EXPIRED_AT
                );
    }

    @Test
    void rejectsHumanApprovalAtExpirationBoundary() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.approval_requests
                        SET status = 'APPROVED',
                            actor_id = ?,
                            rationale = ?,
                            decided_at = ?
                        WHERE id = ?
                        """,
                        "operator-subject-001",
                        "Too late.",
                        EXPIRES_AT,
                        APPROVAL_REQUEST_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsExpirationBeforeConfiguredBoundary() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.approval_requests
                        SET status = 'EXPIRED',
                            expired_at = ?
                        WHERE id = ?
                        """,
                        EXPIRES_AT.minusSeconds(
                        1
                ),
                        APPROVAL_REQUEST_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsModificationOfImmutableApprovalBinding() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.approval_requests
                        SET request_payload_hash = ?
                        WHERE id = ?
                        """,
                        "c".repeat(64),
                        APPROVAL_REQUEST_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "binding and timing fields are immutable"
                );
    }

    @Test
    void rejectsSecondTransitionAfterApprovalBecomesTerminal() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
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
                "Approved after review.",
                HUMAN_DECIDED_AT,
                APPROVAL_REQUEST_ID
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.approval_requests
                        SET status = 'REJECTED',
                            actor_id = ?,
                            rationale = ?,
                            decided_at = ?
                        WHERE id = ?
                        """,
                        "operator-subject-002",
                        "Attempted second resolution.",
                        HUMAN_DECIDED_AT.plusSeconds(
                                1
                        ),
                        APPROVAL_REQUEST_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "terminal approval requests are immutable"
                );
    }

    @Test
    void rejectsDeletionOfApprovalRequest() {
        insertPendingApproval(
                APPROVAL_REQUEST_ID
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        DELETE FROM proofmesh.approval_requests
                        WHERE id = ?
                        """,
                        APPROVAL_REQUEST_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "approval requests cannot be deleted"
                );
    }

    private void insertPendingApproval(
            UUID approvalRequestId
    ) {
        insertApproval(
                approvalRequestId,
                ORGANIZATION_ID,
                ACTION_ID,
                DECISION_ID,
                AGENT_ID,
                "stripe",
                "refund_payment",
                REQUEST_PAYLOAD_HASH,
                REQUESTED_AT,
                EXPIRES_AT,
                "PENDING",
                null,
                null,
                null,
                null
        );
    }

    private void insertApproval(
            UUID approvalRequestId,
            UUID organizationId,
            UUID governedActionId,
            UUID governanceDecisionId,
            UUID agentId,
            String toolName,
            String operationName,
            String requestPayloadHash,
            OffsetDateTime requestedAt,
            OffsetDateTime expiresAt,
            String status,
            String actorId,
            String rationale,
            OffsetDateTime decidedAt,
            OffsetDateTime expiredAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.approval_requests (
                    id,
                    organization_id,
                    governed_action_id,
                    governance_decision_id,
                    agent_id,
                    tool_name,
                    operation_name,
                    request_payload_hash,
                    requested_at,
                    expires_at,
                    status,
                    actor_id,
                    rationale,
                    decided_at,
                    expired_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                )
                """,
                approvalRequestId,
                organizationId,
                governedActionId,
                governanceDecisionId,
                agentId,
                toolName,
                operationName,
                requestPayloadHash,
                requestedAt,
                expiresAt,
                status,
                actorId,
                rationale,
                decidedAt,
                expiredAt
        );
    }

    private void insertOrganization(
            UUID organizationId,
            String slug,
            String name
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
                name,
                "ACTIVE"
        );
    }

    private void insertAgent(
            UUID agentId,
            UUID organizationId,
            String name
    ) {
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
                name,
                "ACTIVE"
        );
    }

    private void insertGovernedAction(
            UUID actionId,
            UUID organizationId,
            UUID agentId,
            String idempotencyKey,
            String payloadHash
    ) {
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
                idempotencyKey,
                "stripe",
                "refund_payment",
                """
                {
                  "amount": 5000,
                  "paymentId": "pay_approval"
                }
                """,
                payloadHash,
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
                POLICY_ID,
                ORGANIZATION_ID,
                "Approval Schema Policy",
                CREATED_AT
        );
    }

    private void insertPublishedPolicyVersion() {
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
                POLICY_VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                1,
                policyDefinitionJson(),
                "d".repeat(64),
                CREATED_AT,
                PUBLISHED_AT
        );
    }

    private void insertRiskAssessment(
            UUID riskAssessmentId,
            UUID governedActionId
    ) {
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
                ORGANIZATION_ID,
                governedActionId,
                "test-v1",
                90,
                """
                [
                  {
                    "code": "HIGH_RISK_OPERATION",
                    "severity": "HIGH",
                    "weight": 90,
                    "explanation": "Authoritative risk for approval schema testing."
                  }
                ]
                """,
                ASSESSED_AT
        );
    }

    private void insertDecision(
            UUID decisionId,
            UUID governedActionId,
            UUID matchedPolicyRuleId,
            String outcome,
            String reasonCode
    ) {
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
                    ?,
                    ?,
                    CAST(? AS jsonb),
                    ?
                )
                """,
                decisionId,
                ORGANIZATION_ID,
                governedActionId,
                POLICY_VERSION_ID,
                matchedPolicyRuleId,
                outcome,
                90,
                """
                [
                  "%s"
                ]
                """.formatted(
                        reasonCode
                ),
                DECIDED_AT
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
                        APPROVAL_POLICY_RULE_ID,
                        DENY_POLICY_RULE_ID
                );
    }
}