package com.proofmesh.controlplane.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class PolicyDefinitionTest {

    @Test
    void sortsRulesByAscendingPriority() {
        PolicyRule lowPriority =
                rule(
                        "31000000-0000-0000-0000-000000000001",
                        300,
                        0,
                        PolicyEffect.ALLOW,
                        "LOW_PRIORITY"
                );

        PolicyRule highPriority =
                rule(
                        "31000000-0000-0000-0000-000000000002",
                        100,
                        80,
                        PolicyEffect.REQUIRE_APPROVAL,
                        "HIGH_RISK"
                );

        PolicyDefinition definition =
                new PolicyDefinition(
                        List.of(
                                lowPriority,
                                highPriority
                        )
                );

        assertThat(
                definition.rules()
        )
                .containsExactly(
                        highPriority,
                        lowPriority
                );
    }

    @Test
    void defensivelyStoresImmutableRuleList() {
        PolicyDefinition definition =
                new PolicyDefinition(
                        List.of(
                                rule(
                                        "31000000-0000-0000-0000-000000000003",
                                        100,
                                        0,
                                        PolicyEffect.ALLOW,
                                        "ALLOW_RULE"
                                )
                        )
                );

        assertThatThrownBy(
                () -> definition
                        .rules()
                        .add(
                                rule(
                                        "31000000-0000-0000-0000-000000000004",
                                        200,
                                        0,
                                        PolicyEffect.DENY,
                                        "DENY_RULE"
                                )
                        )
        )
                .isInstanceOf(
                        UnsupportedOperationException.class
                );
    }

    @Test
    void rejectsDuplicatePriorities() {
        PolicyRule first =
                rule(
                        "31000000-0000-0000-0000-000000000005",
                        100,
                        0,
                        PolicyEffect.ALLOW,
                        "FIRST_RULE"
                );

        PolicyRule second =
                rule(
                        "31000000-0000-0000-0000-000000000006",
                        100,
                        0,
                        PolicyEffect.DENY,
                        "SECOND_RULE"
                );

        assertThatThrownBy(
                () -> new PolicyDefinition(
                        List.of(
                                first,
                                second
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsDuplicateRuleIds() {
        String sharedId =
                "31000000-0000-0000-0000-000000000007";

        PolicyRule first =
                rule(
                        sharedId,
                        100,
                        0,
                        PolicyEffect.ALLOW,
                        "FIRST_RULE"
                );

        PolicyRule second =
                rule(
                        sharedId,
                        200,
                        0,
                        PolicyEffect.DENY,
                        "SECOND_RULE"
                );

        assertThatThrownBy(
                () -> new PolicyDefinition(
                        List.of(
                                first,
                                second
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void exactTargetMatchesToolAndOperation() {
        PolicyTarget target =
                new PolicyTarget(
                        "stripe",
                        "refund_payment"
                );

        assertThat(
                target.matches(
                        new ToolName(
                                "stripe"
                        ),
                        new OperationName(
                                "refund_payment"
                        )
                )
        ).isTrue();

        assertThat(
                target.matches(
                        new ToolName(
                                "stripe"
                        ),
                        new OperationName(
                                "capture_payment"
                        )
                )
        ).isFalse();
    }

    @Test
    void wildcardTargetMatchesAnyToolAndOperation() {
        PolicyTarget target =
                new PolicyTarget(
                        PolicyTarget.ANY,
                        PolicyTarget.ANY
                );

        assertThat(
                target.matches(
                        new ToolName(
                                "stripe"
                        ),
                        new OperationName(
                                "refund_payment"
                        )
                )
        ).isTrue();

        assertThat(
                target.matches(
                        new ToolName(
                                "postgres"
                        ),
                        new OperationName(
                                "delete_customer"
                        )
                )
        ).isTrue();
    }

    @Test
    void rejectsPartialWildcard() {
        assertThatThrownBy(
                () -> new PolicyTarget(
                        "stripe-*",
                        "*"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void riskThresholdMatchesAtAndAboveMinimum() {
        PolicyRiskThreshold threshold =
                new PolicyRiskThreshold(
                        80
                );

        assertThat(
                threshold.matches(79)
        ).isFalse();

        assertThat(
                threshold.matches(80)
        ).isTrue();

        assertThat(
                threshold.matches(100)
        ).isTrue();
    }

    @Test
    void ruleRequiresBothTargetAndRiskToMatch() {
        PolicyRule rule =
                new PolicyRule(
                        new PolicyRuleId(
                                UUID.fromString(
                                        "31000000-0000-0000-0000-000000000008"
                                )
                        ),
                        new PolicyRulePriority(100),
                        new PolicyTarget(
                                "stripe",
                                "refund_payment"
                        ),
                        new PolicyRiskThreshold(80),
                        PolicyEffect.REQUIRE_APPROVAL,
                        new PolicyReasonCode(
                                "HIGH_RISK_REFUND"
                        )
                );

        assertThat(
                rule.matches(
                        new ToolName(
                                "stripe"
                        ),
                        new OperationName(
                                "refund_payment"
                        ),
                        90
                )
        ).isTrue();

        assertThat(
                rule.matches(
                        new ToolName(
                                "stripe"
                        ),
                        new OperationName(
                                "refund_payment"
                        ),
                        20
                )
        ).isFalse();

        assertThat(
                rule.matches(
                        new ToolName(
                                "postgres"
                        ),
                        new OperationName(
                                "refund_payment"
                        ),
                        90
                )
        ).isFalse();
    }

    private PolicyRule rule(
            String id,
            int priority,
            int minimumRisk,
            PolicyEffect effect,
            String reason
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
                        reason
                )
        );
    }
}