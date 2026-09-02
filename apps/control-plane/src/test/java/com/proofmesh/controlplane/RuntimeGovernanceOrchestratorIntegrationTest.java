package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadCanonicalizer;
import com.proofmesh.controlplane.governedaction.ToolName;
import com.proofmesh.controlplane.policy.PolicyDefinition;
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
import com.proofmesh.controlplane.policy.PolicyVersionNumber;
import com.proofmesh.controlplane.policy.PolicyVersionRepository;
import com.proofmesh.controlplane.policy.PolicyVersionState;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentRepository;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceOrchestrator;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceResult;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RuntimeGovernanceOrchestratorIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "91000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "92000000-0000-0000-0000-000000000001"
            );

    private static final UUID UNKNOWN_AGENT_ID =
            UUID.fromString(
                    "92000000-0000-0000-0000-000000000002"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "93000000-0000-0000-0000-000000000001"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "94000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "95000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "96000000-0000-0000-0000-000000000001"
                    )
            );

    private static final UUID BINDING_ID =
            UUID.fromString(
                    "97000000-0000-0000-0000-000000000001"
            );

    private static final UUID RISK_ASSESSMENT_ID_1 =
            UUID.fromString(
                    "98000000-0000-0000-0000-000000000001"
            );

    private static final UUID RISK_ASSESSMENT_ID_2 =
            UUID.fromString(
                    "98000000-0000-0000-0000-000000000002"
            );

    private static final UUID DECISION_ID_1 =
            UUID.fromString(
                    "99000000-0000-0000-0000-000000000001"
            );

    private static final UUID DECISION_ID_2 =
            UUID.fromString(
                    "99000000-0000-0000-0000-000000000002"
            );

    private static final Instant POLICY_CREATED_AT =
            Instant.parse(
                    "2026-09-01T10:00:00Z"
            );

    private static final Instant POLICY_PUBLISHED_AT =
            Instant.parse(
                    "2026-09-01T10:10:00Z"
            );

    private static final Instant BINDING_ACTIVATED_AT =
            Instant.parse(
                    "2026-09-01T10:20:00Z"
            );

    private static final Instant ACTION_CREATED_AT =
            Instant.parse(
                    "2026-09-01T10:25:00Z"
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T10:30:00Z"
            );

    private static final Duration APPROVAL_WINDOW =
            Duration.ofMinutes(
                    15
            );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RuntimeGovernanceOrchestrator orchestrator;

    @Autowired
    GovernedActionRepository governedActionRepository;

    @Autowired
    RequestPayloadCanonicalizer requestPayloadCanonicalizer;

    @Autowired
    PolicyVersionRepository policyVersionRepository;

    @Autowired
    PolicyPublisher policyPublisher;

    @Autowired
    RiskAssessmentRepository riskAssessmentRepository;

    @Autowired
    GovernanceDecisionRepository governanceDecisionRepository;

    @Autowired
    ApprovalRequestRepository approvalRequestRepository;

    @Test
    void unknownAgentFailsClosedBeforeRiskOrDecisionPersistence() {
        insertOrganization();

        GovernedAction governedAction =
                governedAction(
                        UNKNOWN_AGENT_ID
                );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request(
                                governedAction,
                                RISK_ASSESSMENT_ID_1,
                                DECISION_ID_1,
                                riskSignal(
                                        50
                                ),
                                EVALUATED_AT
                        )
                );

        assertFailedClosed(
                result,
                UNKNOWN_AGENT_ID,
                RuntimeGovernanceFailureReason
                        .AGENT_NOT_ACTIVE
        );

        assertNoRiskOrDecision();
    }

    @Test
    void disabledAgentFailsClosedBeforeRiskOrDecisionPersistence() {
        insertOrganization();

        insertAgent(
                "ACTIVE"
        );

        GovernedAction governedAction =
                persistGovernedAction();

        jdbcTemplate.update(
                """
                UPDATE proofmesh.agents
                SET status = 'DISABLED'
                WHERE id = ?
                  AND organization_id = ?
                """,
                AGENT_ID,
                ORGANIZATION_ID
        );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request(
                                governedAction,
                                RISK_ASSESSMENT_ID_1,
                                DECISION_ID_1,
                                riskSignal(
                                        50
                                ),
                                EVALUATED_AT
                        )
                );

        assertFailedClosed(
                result,
                AGENT_ID,
                RuntimeGovernanceFailureReason
                        .AGENT_NOT_ACTIVE
        );

        assertNoRiskOrDecision();
    }

    @Test
    void activeAgentWithoutPolicyBindingFailsClosedBeforeRisk() {
        insertOrganization();

        insertAgent(
                "ACTIVE"
        );

        GovernedAction governedAction =
                persistGovernedAction();

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request(
                                governedAction,
                                RISK_ASSESSMENT_ID_1,
                                DECISION_ID_1,
                                riskSignal(
                                        50
                                ),
                                EVALUATED_AT
                        )
                );

        assertFailedClosed(
                result,
                AGENT_ID,
                RuntimeGovernanceFailureReason
                        .NO_ACTIVE_POLICY_BINDING
        );

        assertNoRiskOrDecision();
    }

    @Test
    void explicitDenyRulePersistsAuthoritativeRiskAndDecision() {
        insertOrganization();

        insertAgent(
                "ACTIVE"
        );

        GovernedAction governedAction =
                persistGovernedAction();

        publishAndBindPolicy(
                0,
                PolicyEffect.DENY,
                "REFUND_BLOCKED"
        );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request(
                                governedAction,
                                RISK_ASSESSMENT_ID_1,
                                DECISION_ID_1,
                                riskSignal(
                                        20
                                ),
                                EVALUATED_AT
                        )
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernanceResult
                                .Governed.class
                );

        RuntimeGovernanceResult.Governed governed =
                (RuntimeGovernanceResult.Governed)
                        result;

        assertThat(
                governed.decision().outcome()
        ).isEqualTo(
                DecisionOutcome.DENY
        );

        assertThat(
                governed.decision()
                        .matchedPolicyRuleId()
        ).isEqualTo(
                POLICY_RULE_ID
        );

        assertThat(
                governed.riskAssessment()
                        .riskScore()
                        .value()
        ).isEqualTo(
                20
        );

        RiskAssessment persistedRisk =
                riskAssessmentRepository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
                        .orElseThrow();

        GovernanceDecision persistedDecision =
                governanceDecisionRepository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
                        .orElseThrow();

        assertThat(
                persistedRisk.id()
        ).isEqualTo(
                RISK_ASSESSMENT_ID_1
        );

        assertThat(
                persistedDecision.id()
        ).isEqualTo(
                DECISION_ID_1
        );

        assertThat(
                persistedDecision.outcome()
        ).isEqualTo(
                DecisionOutcome.DENY
        );

        assertThat(
                persistedDecision.riskScore()
        ).isEqualTo(
                persistedRisk.riskScore()
        );

        assertThat(
                governed.approvalRequest()
        ).isEmpty();

        assertThat(
                approvalRowCount()
        ).isZero();
    }

    @Test
    void approvalRulePersistsRequireApprovalDecision() {
        insertOrganization();

        insertAgent(
                "ACTIVE"
        );

        GovernedAction governedAction =
                persistGovernedAction();

        publishAndBindPolicy(
                80,
                PolicyEffect.REQUIRE_APPROVAL,
                "HIGH_RISK_REFUND"
        );

        RuntimeGovernanceResult result =
                orchestrator.govern(
                        request(
                                governedAction,
                                RISK_ASSESSMENT_ID_1,
                                DECISION_ID_1,
                                riskSignal(
                                        90
                                ),
                                EVALUATED_AT
                        )
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernanceResult
                                .Governed.class
                );

        RuntimeGovernanceResult.Governed governed =
                (RuntimeGovernanceResult.Governed)
                        result;

        assertThat(
                governed.decision().outcome()
        ).isEqualTo(
                DecisionOutcome.REQUIRE_APPROVAL
        );

        assertThat(
                governed.decision()
                        .requiresApproval()
        ).isTrue();

        assertThat(
                governed.decision()
                        .policyVersionId()
        ).isEqualTo(
                POLICY_VERSION_ID
        );

        assertThat(
                governed.decision()
                        .matchedPolicyRuleId()
        ).isEqualTo(
                POLICY_RULE_ID
        );

        assertThat(
                riskAssessmentRepository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
        ).isPresent();

        assertThat(
                governanceDecisionRepository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
        ).isPresent();

        ApprovalRequest approvalRequest =
                governed
                        .approvalRequest()
                        .orElseThrow();

        assertThat(
                approvalRequest.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                approvalRequest.governedActionId()
        ).isEqualTo(
                ACTION_ID
        );

        assertThat(
                approvalRequest.governanceDecisionId()
        ).isEqualTo(
                DECISION_ID_1
        );

        assertThat(
                approvalRequest.agentId()
        ).isEqualTo(
                AGENT_ID
        );

        assertThat(
                approvalRequest.toolName()
        ).isEqualTo(
                governedAction.toolName()
        );

        assertThat(
                approvalRequest.operationName()
        ).isEqualTo(
                governedAction.operationName()
        );

        assertThat(
                approvalRequest.requestPayloadHash()
        ).isEqualTo(
                governedAction.requestPayloadHash()
        );

        assertThat(
                approvalRequest.requestedAt()
        ).isEqualTo(
                EVALUATED_AT
        );

        assertThat(
                approvalRequest.expiresAt()
        ).isEqualTo(
                EVALUATED_AT.plus(
                        APPROVAL_WINDOW
                )
        );

        assertThat(
                approvalRequest.isPending()
        ).isTrue();

        ApprovalRequest persistedApproval =
                approvalRequestRepository
                        .findByOrganizationIdAndGovernanceDecisionId(
                                ORGANIZATION_ID,
                                DECISION_ID_1
                        )
                        .orElseThrow();

        assertThat(
                persistedApproval
        ).isEqualTo(
                approvalRequest
        );

        assertThat(
                approvalRowCount()
        ).isEqualTo(
                1L
        );
    }

    @Test
    void equivalentRetryConvergesOnSingleAuthoritativeRiskAndDecision() {
        insertOrganization();

        insertAgent(
                "ACTIVE"
        );

        GovernedAction governedAction =
                persistGovernedAction();

        publishAndBindPolicy(
                80,
                PolicyEffect.REQUIRE_APPROVAL,
                "HIGH_RISK_REFUND"
        );

        RuntimeGovernanceResult firstResult =
                orchestrator.govern(
                        request(
                                governedAction,
                                RISK_ASSESSMENT_ID_1,
                                DECISION_ID_1,
                                riskSignal(
                                        90
                                ),
                                EVALUATED_AT
                        )
                );

        RuntimeGovernanceResult secondResult =
                orchestrator.govern(
                        request(
                                governedAction,
                                RISK_ASSESSMENT_ID_2,
                                DECISION_ID_2,
                                riskSignal(
                                        90
                                ),
                                EVALUATED_AT.plusSeconds(
                                        10
                                )
                        )
                );

        RuntimeGovernanceResult.Governed first =
                (RuntimeGovernanceResult.Governed)
                        firstResult;

        RuntimeGovernanceResult.Governed second =
                (RuntimeGovernanceResult.Governed)
                        secondResult;

        ApprovalRequest firstApproval =
                first
                        .approvalRequest()
                        .orElseThrow();

        ApprovalRequest secondApproval =
                second
                        .approvalRequest()
                        .orElseThrow();

        assertThat(
                first.riskAssessment().id()
        ).isEqualTo(
                RISK_ASSESSMENT_ID_1
        );

        assertThat(
                second.riskAssessment().id()
        ).isEqualTo(
                first.riskAssessment().id()
        );

        assertThat(
                second.riskAssessment().id()
        ).isNotEqualTo(
                RISK_ASSESSMENT_ID_2
        );

        assertThat(
                first.decision().id()
        ).isEqualTo(
                DECISION_ID_1
        );

        assertThat(
                second.decision().id()
        ).isEqualTo(
                first.decision().id()
        );

        assertThat(
                second.decision().id()
        ).isNotEqualTo(
                DECISION_ID_2
        );

        assertThat(
                second.decision()
                        .hasSameDecisionSemanticsAs(
                                first.decision()
                        )
        ).isTrue();

        assertThat(
                secondApproval.id()
        ).isEqualTo(
                firstApproval.id()
        );

        assertThat(
                secondApproval.requestedAt()
        ).isEqualTo(
                firstApproval.requestedAt()
        );

        assertThat(
                secondApproval.expiresAt()
        ).isEqualTo(
                firstApproval.expiresAt()
        );

        assertThat(
                secondApproval.governanceDecisionId()
        ).isEqualTo(
                first.decision().id()
        );

        assertThat(
                secondApproval.governanceDecisionId()
        ).isNotEqualTo(
                DECISION_ID_2
        );

        assertThat(
                riskRowCount()
        ).isEqualTo(
                1L
        );

        assertThat(
                decisionRowCount()
        ).isEqualTo(
                1L
        );

        assertThat(
                approvalRowCount()
        ).isEqualTo(
                1L
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
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                ORGANIZATION_ID,
                "runtime-governance-integration",
                "Runtime Governance Integration"
        );
    }

    private void insertAgent(
            String status
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
                AGENT_ID,
                ORGANIZATION_ID,
                "Runtime Governance Agent",
                status
        );
    }

    private GovernedAction persistGovernedAction() {
        GovernedAction governedAction =
                governedAction(
                        AGENT_ID
                );

        governedActionRepository.insertIfAbsent(
                governedAction
        );

        return governedAction;
    }

    private GovernedAction governedAction(
            UUID agentId
    ) {
        CanonicalRequestPayload payload =
                requestPayloadCanonicalizer
                        .canonicalize(
                                """
                                {
                                  "paymentId": "pay_runtime_001",
                                  "amount": 5000
                                }
                                """
                        );

        return new GovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                agentId,
                new IdempotencyKey(
                        "runtime-governance-001"
                ),
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                payload,
                ACTION_CREATED_AT
        );
    }

    private void publishAndBindPolicy(
            int minimumRisk,
            PolicyEffect effect,
            String reasonCode
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
                POLICY_ID,
                ORGANIZATION_ID,
                "Runtime Governance Policy",
                offsetDateTime(
                        POLICY_CREATED_AT
                )
        );

        PolicyVersion draft =
                new PolicyVersion(
                        POLICY_VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(
                                1
                        ),
                        PolicyVersionState.DRAFT,
                        policyDefinition(
                                minimumRisk,
                                effect,
                                reasonCode
                        ),
                        null,
                        POLICY_CREATED_AT,
                        null
                );

        policyVersionRepository.insertDraft(
                draft
        );

        PolicyVersion published =
                policyPublisher.publish(
                        draft,
                        POLICY_PUBLISHED_AT
                );

        policyVersionRepository.persistPublication(
                published
        );

        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.agent_policy_bindings (
                    id,
                    organization_id,
                    agent_id,
                    policy_version_id,
                    activated_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID.value(),
                offsetDateTime(
                        BINDING_ACTIVATED_AT
                )
        );
    }

    private PolicyDefinition policyDefinition(
            int minimumRisk,
            PolicyEffect effect,
            String reasonCode
    ) {
        return new PolicyDefinition(
                List.of(
                        new PolicyRule(
                                POLICY_RULE_ID,
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

    private RuntimeGovernanceRequest request(
            GovernedAction governedAction,
            UUID assessmentId,
            UUID decisionId,
            RiskSignal signal,
            Instant evaluatedAt
    ) {
        return new RuntimeGovernanceRequest(
                assessmentId,
                decisionId,
                governedAction,
                List.of(
                        signal
                ),
                evaluatedAt
        );
    }

    private RiskSignal riskSignal(
            int weight
    ) {
        return new RiskSignal(
                new RiskSignalCode(
                        "HIGH_RISK_OPERATION"
                ),
                weight >= 80
                        ? RiskSeverity.HIGH
                        : RiskSeverity.MEDIUM,
                weight,
                "Deterministic integration-test runtime risk."
        );
    }

    private void assertFailedClosed(
            RuntimeGovernanceResult result,
            UUID expectedAgentId,
            RuntimeGovernanceFailureReason reason
    ) {
        assertThat(result)
                .isInstanceOf(
                        RuntimeGovernanceResult
                                .FailedClosed.class
                );

        RuntimeGovernanceResult.FailedClosed failed =
                (RuntimeGovernanceResult.FailedClosed)
                        result;

        assertThat(
                failed.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                failed.agentId()
        ).isEqualTo(
                expectedAgentId
        );

        assertThat(
                failed.governedActionId()
        ).isEqualTo(
                ACTION_ID
        );

        assertThat(
                failed.reason()
        ).isEqualTo(
                reason
        );
    }

    private void assertNoRiskOrDecision() {
        assertThat(
                riskAssessmentRepository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
        ).isEmpty();

        assertThat(
                governanceDecisionRepository
                        .findByOrganizationIdAndGovernedActionId(
                                ORGANIZATION_ID,
                                ACTION_ID
                        )
        ).isEmpty();

        assertThat(
                approvalRowCount()
        ).isZero();
    }

    private Long riskRowCount() {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM proofmesh.risk_assessments
                WHERE organization_id = ?
                  AND governed_action_id = ?
                """,
                Long.class,
                ORGANIZATION_ID,
                ACTION_ID
        );
    }

    private Long decisionRowCount() {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM proofmesh.governance_decisions
                WHERE organization_id = ?
                  AND governed_action_id = ?
                """,
                Long.class,
                ORGANIZATION_ID,
                ACTION_ID
        );
    }

    private Long approvalRowCount() {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM proofmesh.approval_requests
                WHERE organization_id = ?
                  AND governed_action_id = ?
                """,
                Long.class,
                ORGANIZATION_ID,
                ACTION_ID
        );
    }

    private OffsetDateTime offsetDateTime(
            Instant instant
    ) {
        return OffsetDateTime.ofInstant(
                instant,
                ZoneOffset.UTC
        );
    }
}