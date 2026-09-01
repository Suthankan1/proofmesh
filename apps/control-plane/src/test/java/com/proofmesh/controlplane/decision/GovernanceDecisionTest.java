package com.proofmesh.controlplane.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class GovernanceDecisionTest {

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "11000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "12000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "13000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "14000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "15000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant DECIDED_AT =
            Instant.parse(
                    "2026-08-31T10:00:00Z"
            );

    @Test
    void identifiesAllowDecision() {
        GovernanceDecision decision =
                matchedDecision(
                        DecisionOutcome.ALLOW
                );

        assertThat(
                decision.allowsExecution()
        ).isTrue();

        assertThat(
                decision.deniesExecution()
        ).isFalse();

        assertThat(
                decision.requiresApproval()
        ).isFalse();

        assertThat(
                decision.hasMatchedPolicyRule()
        ).isTrue();

        assertThat(
                decision.matchedPolicyRuleId()
        ).isEqualTo(
                POLICY_RULE_ID
        );
    }

    @Test
    void identifiesDenyDecision() {
        GovernanceDecision decision =
                matchedDecision(
                        DecisionOutcome.DENY
                );

        assertThat(
                decision.deniesExecution()
        ).isTrue();

        assertThat(
                decision.allowsExecution()
        ).isFalse();

        assertThat(
                decision.hasMatchedPolicyRule()
        ).isTrue();
    }

    @Test
    void identifiesApprovalDecision() {
        GovernanceDecision decision =
                matchedDecision(
                        DecisionOutcome.REQUIRE_APPROVAL
                );

        assertThat(
                decision.requiresApproval()
        ).isTrue();

        assertThat(
                decision.allowsExecution()
        ).isFalse();
    }

    @Test
    void representsDefaultDenyWithoutMatchedRule() {
        GovernanceDecision decision =
                new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        DecisionOutcome.DENY,
                        new RiskScore(20),
                        List.of(
                                new DecisionReasonCode(
                                        "NO_APPLICABLE_POLICY_RULE"
                                )
                        ),
                        DECIDED_AT
                );

        assertThat(
                decision.deniesExecution()
        ).isTrue();

        assertThat(
                decision.hasMatchedPolicyRule()
        ).isFalse();

        assertThat(
                decision.matchedPolicyRuleId()
        ).isNull();
    }

    @Test
    void rejectsRiskScoreBelowZero() {
        assertThatThrownBy(
                () -> new RiskScore(-1)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsRiskScoreAboveOneHundred() {
        assertThatThrownBy(
                () -> new RiskScore(101)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsDecisionWithoutReasonCodes() {
        assertThatThrownBy(
                () -> new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.DENY,
                        new RiskScore(80),
                        List.of(),
                        DECIDED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsNullReasonCode() {
        List<DecisionReasonCode> reasons =
                new ArrayList<>();

        reasons.add(
                new DecisionReasonCode(
                        "POLICY_RULE_MATCHED"
                )
        );

        reasons.add(null);

        assertThatThrownBy(
                () -> new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.ALLOW,
                        new RiskScore(10),
                        reasons,
                        DECIDED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsDuplicateReasonCodes() {
        DecisionReasonCode reason =
                new DecisionReasonCode(
                        "POLICY_RULE_MATCHED"
                );

        assertThatThrownBy(
                () -> new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.ALLOW,
                        new RiskScore(10),
                        List.of(
                                reason,
                                reason
                        ),
                        DECIDED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void defensivelyCopiesReasonCodes() {
        List<DecisionReasonCode> reasons =
                new ArrayList<>();

        reasons.add(
                new DecisionReasonCode(
                        "POLICY_RULE_MATCHED"
                )
        );

        GovernanceDecision decision =
                new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.ALLOW,
                        new RiskScore(10),
                        reasons,
                        DECIDED_AT
                );

        reasons.add(
                new DecisionReasonCode(
                        "SECOND_REASON"
                )
        );

        assertThat(
                decision.reasonCodes()
        ).hasSize(1);

        assertThatThrownBy(
                () -> decision
                        .reasonCodes()
                        .add(
                                new DecisionReasonCode(
                                        "THIRD_REASON"
                                )
                        )
        )
                .isInstanceOf(
                        UnsupportedOperationException.class
                );
    }

    @Test
    void rejectsInvalidReasonCodeFormat() {
        assertThatThrownBy(
                () -> new DecisionReasonCode(
                        "policy rule matched"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    private GovernanceDecision matchedDecision(
            DecisionOutcome outcome
    ) {
        return new GovernanceDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                GOVERNED_ACTION_ID,
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                outcome,
                new RiskScore(25),
                List.of(
                        new DecisionReasonCode(
                                "POLICY_RULE_MATCHED"
                        )
                ),
                DECIDED_AT
        );
    }

    @Test
    void rejectsAllowDecisionWithoutMatchedRule() {
        assertThatThrownBy(
                () -> new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        DecisionOutcome.ALLOW,
                        new RiskScore(10),
                        List.of(
                                new DecisionReasonCode(
                                        "POLICY_RULE_MATCHED"
                                )
                        ),
                        DECIDED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "matched policy rule"
                );
    }

    @Test
    void rejectsApprovalDecisionWithoutMatchedRule() {
        assertThatThrownBy(
                () -> new GovernanceDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        null,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(80),
                        List.of(
                                new DecisionReasonCode(
                                        "APPROVAL_REQUIRED"
                                )
                        ),
                        DECIDED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "matched policy rule"
                );
    }

    @Test
        void decisionsWithDifferentRecordingMetadataCanHaveSameSemantics() {
        GovernanceDecision first =
                new GovernanceDecision(
                        UUID.fromString(
                                "e1000000-0000-0000-0000-000000000001"
                        ),
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(90),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        ),
                        DECIDED_AT
                );

        GovernanceDecision retry =
                new GovernanceDecision(
                        UUID.fromString(
                                "e1000000-0000-0000-0000-000000000002"
                        ),
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(90),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        ),
                        DECIDED_AT.plusSeconds(5)
                );

        assertThat(
                first.hasSameDecisionSemanticsAs(
                        retry
                )
        ).isTrue();
        }

        @Test
        void decisionsWithDifferentOutcomeDoNotHaveSameSemantics() {
        GovernanceDecision first =
                new GovernanceDecision(
                        UUID.fromString(
                                "e1000000-0000-0000-0000-000000000003"
                        ),
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(90),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        ),
                        DECIDED_AT
                );

        GovernanceDecision conflicting =
                new GovernanceDecision(
                        UUID.fromString(
                                "e1000000-0000-0000-0000-000000000004"
                        ),
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.ALLOW,
                        new RiskScore(90),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        ),
                        DECIDED_AT.plusSeconds(5)
                );

        assertThat(
                first.hasSameDecisionSemanticsAs(
                        conflicting
                )
        ).isFalse();
        }
}