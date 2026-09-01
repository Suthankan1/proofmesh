package com.proofmesh.controlplane.decision.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionCreator;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultGovernanceDecisionCreatorTest {

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "d1000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "d2000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "d3000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "d4000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "d5000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant DECIDED_AT =
            Instant.parse(
                    "2026-09-01T01:30:00Z"
            );

    private GovernanceDecisionCreator creator;

    @BeforeEach
    void setUp() {
        creator =
                new DefaultGovernanceDecisionCreator();
    }

    @Test
    void createsMatchedApprovalDecisionWithExactProvenance() {
        PolicyEvaluationResult evaluationResult =
                new PolicyEvaluationResult.Matched(
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(90),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        )
                );

        GovernanceDecision decision =
                creator.create(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        evaluationResult,
                        DECIDED_AT
                );

        assertThat(
                decision.id()
        ).isEqualTo(
                DECISION_ID
        );

        assertThat(
                decision.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                decision.governedActionId()
        ).isEqualTo(
                GOVERNED_ACTION_ID
        );

        assertThat(
                decision.policyVersionId()
        ).isEqualTo(
                POLICY_VERSION_ID
        );

        assertThat(
                decision.matchedPolicyRuleId()
        ).isEqualTo(
                POLICY_RULE_ID
        );

        assertThat(
                decision.outcome()
        ).isEqualTo(
                DecisionOutcome.REQUIRE_APPROVAL
        );

        assertThat(
                decision.riskScore()
        ).isEqualTo(
                new RiskScore(90)
        );

        assertThat(
                decision.reasonCodes()
        ).containsExactly(
                new DecisionReasonCode(
                        "HIGH_RISK_REFUND"
                )
        );

        assertThat(
                decision.decidedAt()
        ).isEqualTo(
                DECIDED_AT
        );
    }

    @Test
    void createsExplicitMatchedDenyWithRuleProvenance() {
        PolicyEvaluationResult evaluationResult =
                new PolicyEvaluationResult.Matched(
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.DENY,
                        new RiskScore(95),
                        List.of(
                                new DecisionReasonCode(
                                        "PROHIBITED_OPERATION"
                                )
                        )
                );

        GovernanceDecision decision =
                creator.create(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        evaluationResult,
                        DECIDED_AT
                );

        assertThat(
                decision.outcome()
        ).isEqualTo(
                DecisionOutcome.DENY
        );

        assertThat(
                decision.matchedPolicyRuleId()
        ).isEqualTo(
                POLICY_RULE_ID
        );

        assertThat(
                decision.deniesExecution()
        ).isTrue();
    }

    @Test
    void createsDefaultDenyWithoutFabricatingRuleProvenance() {
        PolicyEvaluationResult evaluationResult =
                new PolicyEvaluationResult.DefaultDenied(
                        POLICY_VERSION_ID,
                        new RiskScore(25),
                        new DecisionReasonCode(
                                "NO_APPLICABLE_POLICY_RULE"
                        )
                );

        GovernanceDecision decision =
                creator.create(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        evaluationResult,
                        DECIDED_AT
                );

        assertThat(
                decision.policyVersionId()
        ).isEqualTo(
                POLICY_VERSION_ID
        );

        assertThat(
                decision.matchedPolicyRuleId()
        ).isNull();

        assertThat(
                decision.outcome()
        ).isEqualTo(
                DecisionOutcome.DENY
        );

        assertThat(
                decision.riskScore()
        ).isEqualTo(
                new RiskScore(25)
        );

        assertThat(
                decision.reasonCodes()
        ).containsExactly(
                new DecisionReasonCode(
                        "NO_APPLICABLE_POLICY_RULE"
                )
        );

        assertThat(
                decision.deniesExecution()
        ).isTrue();
    }

    @Test
    void rejectsNullEvaluationResult() {
        assertThatThrownBy(
                () -> creator.create(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        null,
                        DECIDED_AT
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "evaluationResult"
                );
    }
}