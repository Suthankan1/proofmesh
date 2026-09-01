package com.proofmesh.controlplane.policy.internal.serialization;

import com.proofmesh.controlplane.policy.InvalidPolicyDefinitionException;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionParser;
import com.proofmesh.controlplane.policy.PolicyEffect;
import com.proofmesh.controlplane.policy.PolicyReasonCode;
import com.proofmesh.controlplane.policy.PolicyRiskThreshold;
import com.proofmesh.controlplane.policy.PolicyRule;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyRulePriority;
import com.proofmesh.controlplane.policy.PolicyTarget;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
class JacksonPolicyDefinitionParser
        implements PolicyDefinitionParser {

    private final JsonMapper jsonMapper;

    JacksonPolicyDefinitionParser(
            JsonMapper jsonMapper
    ) {
        this.jsonMapper =
                Objects.requireNonNull(
                        jsonMapper,
                        "jsonMapper must not be null"
                );
    }

    @Override
    public PolicyDefinition parse(
            String json
    ) {
        if (json == null
                || json.isBlank()) {
            throw new InvalidPolicyDefinitionException(
                    "policy definition JSON must not be blank"
            );
        }

        try {
            JsonNode root =
                    jsonMapper.readTree(
                            json
                    );

            if (root == null
                    || !root.isObject()) {
                throw new InvalidPolicyDefinitionException(
                        "policy definition must be a JSON object"
                );
            }

            JsonNode rulesNode =
                    root.get(
                            "rules"
                    );

            if (rulesNode == null
                    || !rulesNode.isArray()) {
                throw new InvalidPolicyDefinitionException(
                        "policy definition must contain a rules array"
                );
            }

            if (root.size() != 1) {
                throw new InvalidPolicyDefinitionException(
                        "policy definition contains unsupported fields"
                );
            }

            List<PolicyRule> rules =
                    new ArrayList<>();

            for (JsonNode ruleNode : rulesNode) {
                rules.add(
                        parseRule(
                                ruleNode
                        )
                );
            }

            return new PolicyDefinition(
                    rules
            );
        } catch (InvalidPolicyDefinitionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidPolicyDefinitionException(
                    "invalid policy definition",
                    exception
            );
        }
    }

    private PolicyRule parseRule(
            JsonNode ruleNode
    ) {
        if (ruleNode == null
                || !ruleNode.isObject()) {
            throw new InvalidPolicyDefinitionException(
                    "policy rule must be a JSON object"
            );
        }

        if (ruleNode.size() != 6) {
            throw new InvalidPolicyDefinitionException(
                    "policy rule contains missing or unsupported fields"
            );
        }

        JsonNode targetNode =
                requiredObject(
                        ruleNode,
                        "target"
                );

        if (targetNode.size() != 2) {
            throw new InvalidPolicyDefinitionException(
                    "policy target contains missing or unsupported fields"
            );
        }

        JsonNode riskNode =
                requiredObject(
                        ruleNode,
                        "risk"
                );

        if (riskNode.size() != 1) {
            throw new InvalidPolicyDefinitionException(
                    "policy risk contains missing or unsupported fields"
            );
        }

        return new PolicyRule(
                new PolicyRuleId(
                        UUID.fromString(
                                requiredText(
                                        ruleNode,
                                        "id"
                                )
                        )
                ),
                new PolicyRulePriority(
                        requiredInteger(
                                ruleNode,
                                "priority"
                        )
                ),
                new PolicyTarget(
                        requiredText(
                                targetNode,
                                "tool"
                        ),
                        requiredText(
                                targetNode,
                                "operation"
                        )
                ),
                new PolicyRiskThreshold(
                        requiredInteger(
                                riskNode,
                                "minimum"
                        )
                ),
                PolicyEffect.valueOf(
                        requiredText(
                                ruleNode,
                                "effect"
                        )
                ),
                new PolicyReasonCode(
                        requiredText(
                                ruleNode,
                                "reasonCode"
                        )
                )
        );
    }

    private JsonNode requiredObject(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode value =
                parent.get(
                        fieldName
                );

        if (value == null
                || !value.isObject()) {
            throw new InvalidPolicyDefinitionException(
                    fieldName
                            + " must be a JSON object"
            );
        }

        return value;
    }

    private String requiredText(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode value =
                parent.get(
                        fieldName
                );

        if (value == null
                || !value.isTextual()) {
            throw new InvalidPolicyDefinitionException(
                    fieldName
                            + " must be a string"
            );
        }

        String text =
                value.asText();

        if (text.isBlank()) {
            throw new InvalidPolicyDefinitionException(
                    fieldName
                            + " must not be blank"
            );
        }

        return text;
    }

    private int requiredInteger(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode value =
                parent.get(
                        fieldName
                );

        if (value == null
                || !value.isIntegralNumber()) {
            throw new InvalidPolicyDefinitionException(
                    fieldName
                            + " must be an integer"
            );
        }

        if (!value.canConvertToInt()) {
            throw new InvalidPolicyDefinitionException(
                    fieldName
                            + " must fit within a Java integer"
            );
        }

        return value.intValue();
    }
}