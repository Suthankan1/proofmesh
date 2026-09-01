package com.proofmesh.controlplane.policy;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PolicyDefinition(
        List<PolicyRule> rules
) {

    public PolicyDefinition {
        Objects.requireNonNull(
                rules,
                "rules must not be null"
        );

        if (rules.stream()
                .anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "rules must not contain null values"
            );
        }

        validateUniqueRuleIds(
                rules
        );

        validateUniquePriorities(
                rules
        );

        rules =
                rules.stream()
                        .sorted(
                                Comparator.comparing(
                                        PolicyRule::priority
                                )
                        )
                        .toList();
    }

    private static void validateUniqueRuleIds(
            List<PolicyRule> rules
    ) {
        Set<PolicyRuleId> ids =
                new HashSet<>();

        for (PolicyRule rule : rules) {
            if (!ids.add(
                    rule.id()
            )) {
                throw new IllegalArgumentException(
                        "policy definition must not contain duplicate rule IDs"
                );
            }
        }
    }

    private static void validateUniquePriorities(
            List<PolicyRule> rules
    ) {
        Set<PolicyRulePriority> priorities =
                new HashSet<>();

        for (PolicyRule rule : rules) {
            if (!priorities.add(
                    rule.priority()
            )) {
                throw new IllegalArgumentException(
                        "policy definition must not contain duplicate rule priorities"
                );
            }
        }
    }
}