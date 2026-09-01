package com.proofmesh.controlplane.decision;

import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import java.util.List;
import java.util.Objects;

public sealed interface PolicyEvaluationResult
        permits PolicyEvaluationResult.Matched,
                PolicyEvaluationResult.DefaultDenied {

    record Matched(
            PolicyVersionId policyVersionId,
            PolicyRuleId policyRuleId,
            DecisionOutcome outcome,
            RiskScore riskScore,
            List<DecisionReasonCode> reasonCodes
    ) implements PolicyEvaluationResult {

        public Matched {
            Objects.requireNonNull(
                    policyVersionId,
                    "policyVersionId must not be null"
            );

            Objects.requireNonNull(
                    policyRuleId,
                    "policyRuleId must not be null"
            );

            Objects.requireNonNull(
                    outcome,
                    "outcome must not be null"
            );

            Objects.requireNonNull(
                    riskScore,
                    "riskScore must not be null"
            );

            Objects.requireNonNull(
                    reasonCodes,
                    "reasonCodes must not be null"
            );

            if (reasonCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "reasonCodes must not be empty"
                );
            }

            reasonCodes =
                    List.copyOf(
                            reasonCodes
                    );
        }
    }

    record DefaultDenied(
            PolicyVersionId policyVersionId,
            RiskScore riskScore,
            DecisionReasonCode reasonCode
    ) implements PolicyEvaluationResult {

        public DefaultDenied {
            Objects.requireNonNull(
                    policyVersionId,
                    "policyVersionId must not be null"
            );

            Objects.requireNonNull(
                    riskScore,
                    "riskScore must not be null"
            );

            Objects.requireNonNull(
                    reasonCode,
                    "reasonCode must not be null"
            );
        }
    }
}