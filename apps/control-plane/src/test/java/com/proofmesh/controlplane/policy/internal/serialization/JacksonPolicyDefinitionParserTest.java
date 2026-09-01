package com.proofmesh.controlplane.policy.internal.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.policy.InvalidPolicyDefinitionException;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionParser;
import com.proofmesh.controlplane.policy.PolicyEffect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class JacksonPolicyDefinitionParserTest {

    private PolicyDefinitionParser parser;

    @BeforeEach
    void setUp() {
        parser =
                new JacksonPolicyDefinitionParser(
                        JsonMapper.builder()
                                .build()
                );
    }

    @Test
    void parsesValidPolicyDefinition() {
        PolicyDefinition definition =
                parser.parse(
                        """
                        {
                          "rules": [
                            {
                              "id": "81000000-0000-0000-0000-000000000001",
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
                            }
                          ]
                        }
                        """
                );

        assertThat(
                definition.rules()
        ).hasSize(1);

        assertThat(
                definition.rules()
                        .getFirst()
                        .priority()
                        .value()
        ).isEqualTo(100);

        assertThat(
                definition.rules()
                        .getFirst()
                        .target()
                        .tool()
        ).isEqualTo(
                "stripe"
        );

        assertThat(
                definition.rules()
                        .getFirst()
                        .target()
                        .operation()
        ).isEqualTo(
                "refund_payment"
        );

        assertThat(
                definition.rules()
                        .getFirst()
                        .riskThreshold()
                        .minimum()
        ).isEqualTo(80);

        assertThat(
                definition.rules()
                        .getFirst()
                        .effect()
        ).isEqualTo(
                PolicyEffect.REQUIRE_APPROVAL
        );

        assertThat(
                definition.rules()
                        .getFirst()
                        .reasonCode()
                        .value()
        ).isEqualTo(
                "HIGH_RISK_REFUND"
        );
    }

    @Test
    void parsesEmptyFailClosedDefinition() {
        PolicyDefinition definition =
                parser.parse(
                        """
                        {
                          "rules": []
                        }
                        """
                );

        assertThat(
                definition.rules()
        ).isEmpty();
    }

    @Test
    void sortsPersistedRulesByPriorityThroughDomainInvariant() {
        PolicyDefinition definition =
                parser.parse(
                        """
                        {
                          "rules": [
                            {
                              "id": "81000000-0000-0000-0000-000000000002",
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
                              "id": "81000000-0000-0000-0000-000000000003",
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
                            }
                          ]
                        }
                        """
                );

        assertThat(
                definition.rules()
                        .get(0)
                        .priority()
                        .value()
        ).isEqualTo(100);

        assertThat(
                definition.rules()
                        .get(1)
                        .priority()
                        .value()
        ).isEqualTo(200);
    }

    @Test
    void rejectsMissingRulesArray() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                );
    }

    @Test
    void rejectsRulesWhenNotArray() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                          "rules": {}
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                );
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                          "rules":
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                );
    }

    @Test
    void rejectsRuleMissingRequiredField() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                          "rules": [
                            {
                              "id": "81000000-0000-0000-0000-000000000004",
                              "priority": 100,
                              "target": {
                                "tool": "stripe",
                                "operation": "refund_payment"
                              },
                              "risk": {
                                "minimum": 80
                              },
                              "effect": "ALLOW"
                            }
                          ]
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                );
    }

    @Test
    void rejectsInvalidPolicyDomainValues() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                          "rules": [
                            {
                              "id": "81000000-0000-0000-0000-000000000005",
                              "priority": 100,
                              "target": {
                                "tool": "stripe-*",
                                "operation": "*"
                              },
                              "risk": {
                                "minimum": 0
                              },
                              "effect": "ALLOW",
                              "reasonCode": "INVALID_TARGET"
                            }
                          ]
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                );
    }

    @Test
    void rejectsUnknownRootField() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                        "rules": [],
                        "unexpected": true
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                )
                .hasMessageContaining(
                        "unsupported fields"
                );
    }

    @Test
    void rejectsUnknownRuleField() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                        "rules": [
                            {
                            "id": "81000000-0000-0000-0000-000000000006",
                            "priority": 100,
                            "target": {
                                "tool": "stripe",
                                "operation": "refund_payment"
                            },
                            "risk": {
                                "minimum": 80
                            },
                            "effect": "ALLOW",
                            "reasonCode": "STANDARD_REFUND",
                            "unexpected": true
                            }
                        ]
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                )
                .hasMessageContaining(
                        "unsupported fields"
                );
    }

    @Test
    void rejectsUnknownTargetField() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                        "rules": [
                            {
                            "id": "81000000-0000-0000-0000-000000000007",
                            "priority": 100,
                            "target": {
                                "tool": "stripe",
                                "operation": "refund_payment",
                                "unexpected": true
                            },
                            "risk": {
                                "minimum": 80
                            },
                            "effect": "ALLOW",
                            "reasonCode": "STANDARD_REFUND"
                            }
                        ]
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                )
                .hasMessageContaining(
                        "unsupported fields"
                );
    }

    @Test
    void rejectsUnknownRiskField() {
        assertThatThrownBy(
                () -> parser.parse(
                        """
                        {
                        "rules": [
                            {
                            "id": "81000000-0000-0000-0000-000000000008",
                            "priority": 100,
                            "target": {
                                "tool": "stripe",
                                "operation": "refund_payment"
                            },
                            "risk": {
                                "minimum": 80,
                                "unexpected": true
                            },
                            "effect": "ALLOW",
                            "reasonCode": "STANDARD_REFUND"
                            }
                        ]
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidPolicyDefinitionException.class
                )
                .hasMessageContaining(
                        "unsupported fields"
                );
    }
}