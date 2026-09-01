package com.proofmesh.controlplane.decision.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.PolicyEvaluator;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionHash;
import com.proofmesh.controlplane.policy.PolicyEffect;
import com.proofmesh.controlplane.policy.PolicyReasonCode;
import com.proofmesh.controlplane.policy.PolicyRiskThreshold;
import com.proofmesh.controlplane.policy.PolicyRule;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyRulePriority;
import com.proofmesh.controlplane.policy.PolicyTarget;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.policy.PolicyVersionNumber;
import com.proofmesh.controlplane.policy.PolicyVersionState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultPolicyEvaluatorTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "41000000-0000-0000-0000-000000000001"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "41000000-0000-0000-0000-000000000002"
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "42000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId
            POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "43000000-0000-0000-0000-000000000001"
                    )
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "44000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "45000000-0000-0000-0000-000000000001"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-31T10:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-31T10:30:00Z"
            );

    private static final PolicyDefinitionHash
            DEFINITION_HASH =
            new PolicyDefinitionHash(
                    "a".repeat(64)
            );

    private PolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator =
                new DefaultPolicyEvaluator();
    }

    @Test
    void firstMatchingRuleWinsByPriority() {
        PolicyRule highRiskApproval =
                rule(
                        "46000000-0000-0000-0000-000000000001",
                        100,
                        80,
                        PolicyEffect.REQUIRE_APPROVAL,
                        "HIGH_RISK_REFUND"
                );

        PolicyRule ordinaryAllow =
                rule(
                        "46000000-0000-0000-0000-000000000002",
                        200,
                        0,
                        PolicyEffect.ALLOW,
                        "STANDARD_REFUND"
                );

        PolicyVersion policyVersion =
                publishedVersion(
                        List.of(
                                ordinaryAllow,
                                highRiskApproval
                        )
                );

        PolicyEvaluationResult result =
                evaluator.evaluate(
                        policyVersion,
                        governedAction(),
                        new RiskScore(90)
                );

        assertThat(result)
                .isInstanceOf(
                        PolicyEvaluationResult
                                .Matched.class
                );

        PolicyEvaluationResult.Matched matched =
                (PolicyEvaluationResult.Matched)
                        result;

        assertThat(matched.policyRuleId())
                .isEqualTo(
                        highRiskApproval.id()
                );

        assertThat(matched.outcome())
                .isEqualTo(
                        DecisionOutcome.REQUIRE_APPROVAL
                );

        assertThat(
                matched.reasonCodes()
                        .getFirst()
                        .value()
        )
                .isEqualTo(
                        "HIGH_RISK_REFUND"
                );
    }

    @Test
    void fallsThroughToLowerPriorityRule() {
        PolicyVersion policyVersion =
                publishedVersion(
                        List.of(
                                rule(
                                        "46000000-0000-0000-0000-000000000003",
                                        100,
                                        80,
                                        PolicyEffect.REQUIRE_APPROVAL,
                                        "HIGH_RISK_REFUND"
                                ),
                                rule(
                                        "46000000-0000-0000-0000-000000000004",
                                        200,
                                        0,
                                        PolicyEffect.ALLOW,
                                        "STANDARD_REFUND"
                                )
                        )
                );

        PolicyEvaluationResult result =
                evaluator.evaluate(
                        policyVersion,
                        governedAction(),
                        new RiskScore(25)
                );

        PolicyEvaluationResult.Matched matched =
                (PolicyEvaluationResult.Matched)
                        result;

        assertThat(matched.outcome())
                .isEqualTo(
                        DecisionOutcome.ALLOW
                );

        assertThat(
                matched.reasonCodes()
                        .getFirst()
                        .value()
        )
                .isEqualTo(
                        "STANDARD_REFUND"
                );
    }

    @Test
    void mapsExplicitDenyRule() {
        PolicyVersion policyVersion =
                publishedVersion(
                        List.of(
                                rule(
                                        "46000000-0000-0000-0000-000000000005",
                                        100,
                                        0,
                                        PolicyEffect.DENY,
                                        "REFUND_BLOCKED"
                                )
                        )
                );

        PolicyEvaluationResult result =
                evaluator.evaluate(
                        policyVersion,
                        governedAction(),
                        new RiskScore(10)
                );

        PolicyEvaluationResult.Matched matched =
                (PolicyEvaluationResult.Matched)
                        result;

        assertThat(matched.outcome())
                .isEqualTo(
                        DecisionOutcome.DENY
                );
    }

    @Test
    void deniesWhenNoRuleMatches() {
        PolicyVersion policyVersion =
                publishedVersion(
                        List.of(
                                rule(
                                        "46000000-0000-0000-0000-000000000006",
                                        100,
                                        80,
                                        PolicyEffect.REQUIRE_APPROVAL,
                                        "HIGH_RISK_REFUND"
                                )
                        )
                );

        PolicyEvaluationResult result =
                evaluator.evaluate(
                        policyVersion,
                        governedAction(),
                        new RiskScore(20)
                );

        assertThat(result)
                .isInstanceOf(
                        PolicyEvaluationResult
                                .DefaultDenied.class
                );

        PolicyEvaluationResult.DefaultDenied denied =
                (PolicyEvaluationResult.DefaultDenied)
                        result;

        assertThat(
                denied.reasonCode().value()
        )
                .isEqualTo(
                        "NO_APPLICABLE_POLICY_RULE"
                );
    }

    @Test
    void draftPolicyCannotAuthorizeAction() {
        PolicyVersion draft =
                new PolicyVersion(
                        POLICY_VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.DRAFT,
                        new PolicyDefinition(
                                List.of(
                                        rule(
                                                "46000000-0000-0000-0000-000000000007",
                                                100,
                                                0,
                                                PolicyEffect.ALLOW,
                                                "ALLOW_ALL_REFUNDS"
                                        )
                                )
                        ),
                        null,
                        CREATED_AT,
                        null
                );

        PolicyEvaluationResult result =
                evaluator.evaluate(
                        draft,
                        governedAction(),
                        new RiskScore(0)
                );

        assertThat(result)
                .isInstanceOf(
                        PolicyEvaluationResult
                                .DefaultDenied.class
                );

        PolicyEvaluationResult.DefaultDenied denied =
                (PolicyEvaluationResult.DefaultDenied)
                        result;

        assertThat(
                denied.reasonCode().value()
        )
                .isEqualTo(
                        "POLICY_VERSION_NOT_PUBLISHED"
                );
    }

    @Test
    void rejectsCrossOrganizationPolicyByDenying() {
        PolicyVersion otherOrganizationPolicy =
                new PolicyVersion(
                        POLICY_VERSION_ID,
                        POLICY_ID,
                        OTHER_ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.PUBLISHED,
                        new PolicyDefinition(
                                List.of(
                                        rule(
                                                "46000000-0000-0000-0000-000000000008",
                                                100,
                                                0,
                                                PolicyEffect.ALLOW,
                                                "ALLOW_ALL_REFUNDS"
                                        )
                                )
                        ),
                        DEFINITION_HASH,
                        CREATED_AT,
                        PUBLISHED_AT
                );

        PolicyEvaluationResult result =
                evaluator.evaluate(
                        otherOrganizationPolicy,
                        governedAction(),
                        new RiskScore(0)
                );

        assertThat(result)
                .isInstanceOf(
                        PolicyEvaluationResult
                                .DefaultDenied.class
                );

        PolicyEvaluationResult.DefaultDenied denied =
                (PolicyEvaluationResult.DefaultDenied)
                        result;

        assertThat(
                denied.reasonCode().value()
        )
                .isEqualTo(
                        "POLICY_ORGANIZATION_MISMATCH"
                );
    }

    @Test
    void emptyPublishedPolicyFailsClosed() {
        PolicyVersion policyVersion =
                publishedVersion(
                        List.of()
                );

        PolicyEvaluationResult result =
                evaluator.evaluate(
                        policyVersion,
                        governedAction(),
                        new RiskScore(0)
                );

        assertThat(result)
                .isInstanceOf(
                        PolicyEvaluationResult
                                .DefaultDenied.class
                );

        PolicyEvaluationResult.DefaultDenied denied =
                (PolicyEvaluationResult.DefaultDenied)
                        result;

        assertThat(
                denied.reasonCode().value()
        )
                .isEqualTo(
                        "NO_APPLICABLE_POLICY_RULE"
                );
    }

    private PolicyVersion publishedVersion(
            List<PolicyRule> rules
    ) {
        return new PolicyVersion(
                POLICY_VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                new PolicyVersionNumber(1),
                PolicyVersionState.PUBLISHED,
                new PolicyDefinition(
                        rules
                ),
                DEFINITION_HASH,
                CREATED_AT,
                PUBLISHED_AT
        );
    }

    private PolicyRule rule(
            String id,
            int priority,
            int minimumRisk,
            PolicyEffect effect,
            String reasonCode
    ) {
        return new PolicyRule(
                new PolicyRuleId(
                        UUID.fromString(id)
                ),
                new PolicyRulePriority(
                        priority
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
        );
    }

    private GovernedAction governedAction() {
        return new GovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey(
                        "policy-evaluation-001"
                ),
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                new CanonicalRequestPayload(
                        "{\"amount\":5000}",
                        new RequestPayloadHash(
                                "a".repeat(64)
                        )
                ),
                CREATED_AT
        );
    }
}