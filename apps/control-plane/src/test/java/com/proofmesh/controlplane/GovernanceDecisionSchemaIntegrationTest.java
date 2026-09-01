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
class GovernanceDecisionSchemaIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "b1000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "b1000000-0000-0000-0000-000000000002"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "b2000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_AGENT_ID =
            UUID.fromString(
                    "b2000000-0000-0000-0000-000000000002"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "b3000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ACTION_ID =
            UUID.fromString(
                    "b3000000-0000-0000-0000-000000000002"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "b4000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_POLICY_ID =
            UUID.fromString(
                    "b4000000-0000-0000-0000-000000000002"
            );

    private static final UUID POLICY_VERSION_ID =
            UUID.fromString(
                    "b5000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_POLICY_VERSION_ID =
            UUID.fromString(
                    "b5000000-0000-0000-0000-000000000002"
            );

    private static final UUID DRAFT_POLICY_VERSION_ID =
            UUID.fromString(
                    "b5000000-0000-0000-0000-000000000003"
            );

    private static final UUID POLICY_RULE_ID =
            UUID.fromString(
                    "b6000000-0000-0000-0000-000000000001"
            );

    private static final UUID ALLOW_POLICY_RULE_ID =
            UUID.fromString(
                    "b6000000-0000-0000-0000-000000000002"
            );

    private static final UUID DENY_POLICY_RULE_ID =
            UUID.fromString(
                    "b6000000-0000-0000-0000-000000000003"
            );

    private static final UUID UNKNOWN_POLICY_RULE_ID =
            UUID.fromString(
                    "b6000000-0000-0000-0000-000000000099"
            );

    private static final UUID RISK_ASSESSMENT_ID =
            UUID.fromString(
                    "b6500000-0000-0000-0000-000000000001"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "b7000000-0000-0000-0000-000000000001"
            );

    private static final UUID SECOND_DECISION_ID =
            UUID.fromString(
                    "b7000000-0000-0000-0000-000000000002"
            );

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T00:00:00Z"
            );

    private static final OffsetDateTime PUBLISHED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T00:30:00Z"
            );

    private static final OffsetDateTime ASSESSED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T00:40:00Z"
            );

    private static final OffsetDateTime DECIDED_AT =
            OffsetDateTime.parse(
                    "2026-09-01T00:45:00Z"
            );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insertOrganization(
                ORGANIZATION_ID,
                "decision-primary",
                "Decision Primary"
        );

        insertOrganization(
                OTHER_ORGANIZATION_ID,
                "decision-other",
                "Decision Other"
        );

        insertAgent(
                AGENT_ID,
                ORGANIZATION_ID,
                "Primary Agent"
        );

        insertAgent(
                OTHER_AGENT_ID,
                OTHER_ORGANIZATION_ID,
                "Other Agent"
        );

        insertGovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                "decision-action-primary"
        );

        insertGovernedAction(
                OTHER_ACTION_ID,
                OTHER_ORGANIZATION_ID,
                OTHER_AGENT_ID,
                "decision-action-other"
        );

        insertPolicy(
                POLICY_ID,
                ORGANIZATION_ID,
                "Primary Decision Policy"
        );

        insertPolicy(
                OTHER_POLICY_ID,
                OTHER_ORGANIZATION_ID,
                "Other Decision Policy"
        );

        insertPublishedPolicyVersion(
                POLICY_VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                1
        );

        insertPublishedPolicyVersion(
                OTHER_POLICY_VERSION_ID,
                OTHER_POLICY_ID,
                OTHER_ORGANIZATION_ID,
                1
        );

        insertDraftPolicyVersion(
                DRAFT_POLICY_VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                2
        );

        insertRiskAssessment(
                RISK_ASSESSMENT_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                90
        );
    }

    @Test
    void storesDecisionWithExactPublishedPolicyAndMatchedRule() {
        insertDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                "REQUIRE_APPROVAL",
                90,
                """
                [
                  "HIGH_RISK_REFUND"
                ]
                """
        );

        String outcome =
                jdbcTemplate.queryForObject(
                        """
                        SELECT outcome
                        FROM proofmesh.governance_decisions
                        WHERE id = ?
                        """,
                        String.class,
                        DECISION_ID
                );

        Integer riskScore =
                jdbcTemplate.queryForObject(
                        """
                        SELECT risk_score
                        FROM proofmesh.governance_decisions
                        WHERE id = ?
                        """,
                        Integer.class,
                        DECISION_ID
                );

        UUID policyVersionId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT policy_version_id
                        FROM proofmesh.governance_decisions
                        WHERE id = ?
                        """,
                        UUID.class,
                        DECISION_ID
                );

        UUID matchedRuleId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT matched_policy_rule_id
                        FROM proofmesh.governance_decisions
                        WHERE id = ?
                        """,
                        UUID.class,
                        DECISION_ID
                );

        assertThat(outcome)
                .isEqualTo(
                        "REQUIRE_APPROVAL"
                );

        assertThat(riskScore)
                .isEqualTo(
                        90
                );

        assertThat(policyVersionId)
                .isEqualTo(
                        POLICY_VERSION_ID
                );

        assertThat(matchedRuleId)
                .isEqualTo(
                        POLICY_RULE_ID
                );
    }

    @Test
    void storesDefaultDenyWithoutMatchedPolicyRule() {
        insertDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                null,
                "DENY",
                90,
                """
                [
                  "NO_APPLICABLE_POLICY_RULE"
                ]
                """
        );

        UUID matchedRuleId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT matched_policy_rule_id
                        FROM proofmesh.governance_decisions
                        WHERE id = ?
                        """,
                        UUID.class,
                        DECISION_ID
                );

        assertThat(matchedRuleId)
                .isNull();
    }

    @Test
    void rejectsCrossOrganizationGovernedAction() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        OTHER_ACTION_ID,
                        POLICY_VERSION_ID,
                        DENY_POLICY_RULE_ID,
                        "DENY",
                        90,
                        """
                        [
                          "REFUND_BLOCKED"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsCrossOrganizationPolicyVersion() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        OTHER_POLICY_VERSION_ID,
                        DENY_POLICY_RULE_ID,
                        "DENY",
                        90,
                        """
                        [
                          "REFUND_BLOCKED"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsDecisionReferencingDraftPolicyVersion() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        DRAFT_POLICY_VERSION_ID,
                        ALLOW_POLICY_RULE_ID,
                        "ALLOW",
                        90,
                        """
                        [
                          "STANDARD_REFUND"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "published policy version"
                );
    }

    @Test
    void rejectsMatchedRuleNotPresentInReferencedPolicyVersion() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        UNKNOWN_POLICY_RULE_ID,
                        "ALLOW",
                        90,
                        """
                        [
                          "STANDARD_REFUND"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "matched policy rule does not exist"
                );
    }

    @Test
    void rejectsAllowWithoutMatchedPolicyRule() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        "ALLOW",
                        90,
                        """
                        [
                          "STANDARD_REFUND"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsRequireApprovalWithoutMatchedPolicyRule() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        "REQUIRE_APPROVAL",
                        90,
                        """
                        [
                          "HIGH_RISK_REFUND"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsEmptyReasonCodes() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        "DENY",
                        90,
                        """
                        []
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsDuplicateReasonCodes() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        "DENY",
                        90,
                        """
                        [
                          "NO_APPLICABLE_POLICY_RULE",
                          "NO_APPLICABLE_POLICY_RULE"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsInvalidReasonCodeFormat() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        "DENY",
                        90,
                        """
                        [
                          "invalid reason code"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsSecondDecisionForSameGovernedAction() {
        insertDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                ALLOW_POLICY_RULE_ID,
                "ALLOW",
                90,
                """
                [
                  "STANDARD_REFUND"
                ]
                """
        );

        assertThatThrownBy(
                () -> insertDecision(
                        SECOND_DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        ALLOW_POLICY_RULE_ID,
                        "ALLOW",
                        90,
                        """
                        [
                          "STANDARD_REFUND"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void rejectsOutcomeThatDoesNotMatchMatchedRuleEffect() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        "ALLOW",
                        90,
                        """
                        [
                          "HIGH_RISK_REFUND"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "outcome does not match"
                );
    }

    @Test
    void rejectsDecisionMissingMatchedRuleReasonCode() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        "REQUIRE_APPROVAL",
                        90,
                        """
                        [
                          "RUNTIME_CONTEXT_VERIFIED"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "must include the matched policy rule reason code"
                );
    }

    @Test
    void acceptsSupplementalReasonsWhenMatchedRuleReasonIsPresent() {
        insertDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                "REQUIRE_APPROVAL",
                90,
                """
                [
                  "HIGH_RISK_REFUND",
                  "RUNTIME_CONTEXT_VERIFIED"
                ]
                """
        );

        String reasonCodes =
                jdbcTemplate.queryForObject(
                        """
                        SELECT reason_codes::TEXT
                        FROM proofmesh.governance_decisions
                        WHERE id = ?
                        """,
                        String.class,
                        DECISION_ID
                );

        assertThat(reasonCodes)
                .contains(
                        "HIGH_RISK_REFUND"
                )
                .contains(
                        "RUNTIME_CONTEXT_VERIFIED"
                );
    }

    @Test
    void rejectsDecisionWithoutAuthoritativeRiskAssessment() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        OTHER_ORGANIZATION_ID,
                        OTHER_ACTION_ID,
                        OTHER_POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        "REQUIRE_APPROVAL",
                        90,
                        """
                        [
                          "HIGH_RISK_REFUND"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "requires an authoritative risk assessment"
                );
    }

    @Test
    void rejectsDecisionWhoseRiskScoreDiffersFromAuthoritativeAssessment() {
        assertThatThrownBy(
                () -> insertDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        "REQUIRE_APPROVAL",
                        80,
                        """
                        [
                          "HIGH_RISK_REFUND"
                        ]
                        """
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "risk score does not match the authoritative risk assessment"
                );
    }

    @Test
    void rejectsModificationOfPersistedDecision() {
        insertDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                DENY_POLICY_RULE_ID,
                "DENY",
                90,
                """
                [
                  "REFUND_BLOCKED"
                ]
                """
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        UPDATE proofmesh.governance_decisions
                        SET outcome = 'ALLOW'
                        WHERE id = ?
                        """,
                        DECISION_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "governance decisions are immutable"
                );
    }

    @Test
    void rejectsDeletionOfPersistedDecision() {
        insertDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                DENY_POLICY_RULE_ID,
                "DENY",
                90,
                """
                [
                  "REFUND_BLOCKED"
                ]
                """
        );

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        DELETE FROM proofmesh.governance_decisions
                        WHERE id = ?
                        """,
                        DECISION_ID
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining(
                        "governance decisions cannot be deleted"
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
            String idempotencyKey
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
                  "paymentId": "pay_123"
                }
                """,
                "a".repeat(64),
                CREATED_AT
        );
    }

    private void insertRiskAssessment(
            UUID riskAssessmentId,
            UUID organizationId,
            UUID governedActionId,
            int riskScore
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
                organizationId,
                governedActionId,
                "test-v1",
                riskScore,
                """
                [
                  {
                    "code": "HIGH_RISK_OPERATION",
                    "severity": "HIGH",
                    "weight": 90,
                    "explanation": "Authoritative risk assessment for governance decision schema integration."
                  }
                ]
                """,
                ASSESSED_AT
        );
    }

    private void insertPolicy(
            UUID policyId,
            UUID organizationId,
            String name
    ) {
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
                name,
                CREATED_AT
        );
    }

    private void insertPublishedPolicyVersion(
            UUID policyVersionId,
            UUID policyId,
            UUID organizationId,
            int versionNumber
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
                policyVersionId,
                policyId,
                organizationId,
                versionNumber,
                policyDefinitionJson(),
                "a".repeat(64),
                CREATED_AT,
                PUBLISHED_AT
        );
    }

    private void insertDraftPolicyVersion(
            UUID policyVersionId,
            UUID policyId,
            UUID organizationId,
            int versionNumber
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
                policyVersionId,
                policyId,
                organizationId,
                versionNumber,
                policyDefinitionJson(),
                CREATED_AT
        );
    }

    private void insertDecision(
            UUID decisionId,
            UUID organizationId,
            UUID governedActionId,
            UUID policyVersionId,
            UUID matchedPolicyRuleId,
            String outcome,
            int riskScore,
            String reasonCodes
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
                    CAST(? AS uuid),
                    ?,
                    ?,
                    CAST(? AS jsonb),
                    ?
                )
                """,
                decisionId,
                organizationId,
                governedActionId,
                policyVersionId,
                matchedPolicyRuleId,
                outcome,
                riskScore,
                reasonCodes,
                DECIDED_AT
        );
    }

    private String policyDefinitionJson() {
        return """
                {
                  "rules": [
                    {
                      "id": "b6000000-0000-0000-0000-000000000001",
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
                      "id": "b6000000-0000-0000-0000-000000000002",
                      "priority": 200,
                      "target": {
                        "tool": "stripe",
                        "operation": "refund_payment"
                      },
                      "risk": {
                        "minimum": 0
                      },
                      "effect": "ALLOW",
                      "reasonCode": "STANDARD_REFUND"
                    },
                    {
                      "id": "b6000000-0000-0000-0000-000000000003",
                      "priority": 300,
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
                """;
    }
}