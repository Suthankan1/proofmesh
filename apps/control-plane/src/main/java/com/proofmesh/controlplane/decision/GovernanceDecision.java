package com.proofmesh.controlplane.decision;

import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record GovernanceDecision(
        UUID id,
        UUID organizationId,
        UUID governedActionId,
        PolicyVersionId policyVersionId,
        PolicyRuleId matchedPolicyRuleId,
        DecisionOutcome outcome,
        RiskScore riskScore,
        List<DecisionReasonCode> reasonCodes,
        Instant decidedAt
) {

    public GovernanceDecision {
        Objects.requireNonNull(
                id,
                "id must not be null"
        );

        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                governedActionId,
                "governedActionId must not be null"
        );

        Objects.requireNonNull(
                policyVersionId,
                "policyVersionId must not be null"
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

        Objects.requireNonNull(
                decidedAt,
                "decidedAt must not be null"
        );

        if (outcome != DecisionOutcome.DENY
                && matchedPolicyRuleId == null) {
            throw new IllegalArgumentException(
                    "non-deny decisions must reference a matched policy rule"
            );
        }

        if (reasonCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "reasonCodes must not be empty"
            );
        }

        if (reasonCodes.stream()
                .anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "reasonCodes must not contain null values"
            );
        }

        if (reasonCodes.stream()
                .distinct()
                .count()
                != reasonCodes.size()) {
            throw new IllegalArgumentException(
                    "reasonCodes must not contain duplicates"
            );
        }

        reasonCodes =
                List.copyOf(
                        reasonCodes
                );
    }

    public boolean allowsExecution() {
        return outcome
                == DecisionOutcome.ALLOW;
    }

    public boolean deniesExecution() {
        return outcome
                == DecisionOutcome.DENY;
    }

    public boolean requiresApproval() {
        return outcome
                == DecisionOutcome.REQUIRE_APPROVAL;
    }

    public boolean hasMatchedPolicyRule() {
        return matchedPolicyRuleId != null;
    }

    public boolean hasSameDecisionSemanticsAs(
                GovernanceDecision other
        ) {
        if (other == null) {
                return false;
        }

        return organizationId.equals(
                other.organizationId
        )
                && governedActionId.equals(
                        other.governedActionId
                )
                && policyVersionId.equals(
                        other.policyVersionId
                )
                && Objects.equals(
                        matchedPolicyRuleId,
                        other.matchedPolicyRuleId
                )
                && outcome == other.outcome
                && riskScore.equals(
                        other.riskScore
                )
                && reasonCodes.equals(
                        other.reasonCodes
                );
        }
}