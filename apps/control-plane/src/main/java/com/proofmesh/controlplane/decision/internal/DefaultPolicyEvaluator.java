package com.proofmesh.controlplane.decision.internal;

import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.PolicyEvaluator;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.policy.PolicyEffect;
import com.proofmesh.controlplane.policy.PolicyRule;
import com.proofmesh.controlplane.policy.PolicyVersion;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
class DefaultPolicyEvaluator
        implements PolicyEvaluator {

    private static final DecisionReasonCode
            POLICY_VERSION_NOT_PUBLISHED =
            new DecisionReasonCode(
                    "POLICY_VERSION_NOT_PUBLISHED"
            );

    private static final DecisionReasonCode
            POLICY_ORGANIZATION_MISMATCH =
            new DecisionReasonCode(
                    "POLICY_ORGANIZATION_MISMATCH"
            );

    private static final DecisionReasonCode
            NO_APPLICABLE_POLICY_RULE =
            new DecisionReasonCode(
                    "NO_APPLICABLE_POLICY_RULE"
            );

    @Override
    public PolicyEvaluationResult evaluate(
            PolicyVersion policyVersion,
            GovernedAction governedAction,
            RiskScore riskScore
    ) {
        Objects.requireNonNull(
                policyVersion,
                "policyVersion must not be null"
        );

        Objects.requireNonNull(
                governedAction,
                "governedAction must not be null"
        );

        Objects.requireNonNull(
                riskScore,
                "riskScore must not be null"
        );

        if (!policyVersion.isPublished()) {
            return defaultDeny(
                    policyVersion,
                    riskScore,
                    POLICY_VERSION_NOT_PUBLISHED
            );
        }

        if (!governedAction
                .belongsToOrganization(
                        policyVersion.organizationId()
                )) {
            return defaultDeny(
                    policyVersion,
                    riskScore,
                    POLICY_ORGANIZATION_MISMATCH
            );
        }

        for (PolicyRule rule
                : policyVersion
                        .definition()
                        .rules()) {

            if (rule.matches(
                    governedAction.toolName(),
                    governedAction.operationName(),
                    riskScore.value()
            )) {
                return matched(
                        policyVersion,
                        rule,
                        riskScore
                );
            }
        }

        return defaultDeny(
                policyVersion,
                riskScore,
                NO_APPLICABLE_POLICY_RULE
        );
    }

    private PolicyEvaluationResult matched(
            PolicyVersion policyVersion,
            PolicyRule rule,
            RiskScore riskScore
    ) {
        DecisionReasonCode reasonCode =
                new DecisionReasonCode(
                        rule.reasonCode().value()
                );

        return new PolicyEvaluationResult.Matched(
                policyVersion.id(),
                rule.id(),
                toDecisionOutcome(
                        rule.effect()
                ),
                riskScore,
                List.of(
                        reasonCode
                )
        );
    }

    private PolicyEvaluationResult defaultDeny(
            PolicyVersion policyVersion,
            RiskScore riskScore,
            DecisionReasonCode reasonCode
    ) {
        return new PolicyEvaluationResult.DefaultDenied(
                policyVersion.id(),
                riskScore,
                reasonCode
        );
    }

    private DecisionOutcome toDecisionOutcome(
            PolicyEffect effect
    ) {
        return switch (effect) {
            case ALLOW ->
                    DecisionOutcome.ALLOW;

            case DENY ->
                    DecisionOutcome.DENY;

            case REQUIRE_APPROVAL ->
                    DecisionOutcome.REQUIRE_APPROVAL;
        };
    }
}