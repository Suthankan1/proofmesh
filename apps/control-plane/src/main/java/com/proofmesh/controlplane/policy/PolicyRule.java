package com.proofmesh.controlplane.policy;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.ToolName;

import java.util.Objects;

public record PolicyRule(
        PolicyRuleId id,
        PolicyRulePriority priority,
        PolicyTarget target,
        PolicyRiskThreshold riskThreshold,
        PolicyEffect effect,
        PolicyReasonCode reasonCode
) {

    public PolicyRule {
        Objects.requireNonNull(
                id,
                "id must not be null"
        );

        Objects.requireNonNull(
                priority,
                "priority must not be null"
        );

        Objects.requireNonNull(
                target,
                "target must not be null"
        );

        Objects.requireNonNull(
                riskThreshold,
                "riskThreshold must not be null"
        );

        Objects.requireNonNull(
                effect,
                "effect must not be null"
        );

        Objects.requireNonNull(
                reasonCode,
                "reasonCode must not be null"
        );
    }

    public boolean matches(
            ToolName toolName,
            OperationName operationName,
            int riskScore
    ) {
        return target.matches(
                toolName,
                operationName
        )
                && riskThreshold.matches(
                        riskScore
                );
    }
}