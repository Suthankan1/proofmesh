package com.proofmesh.controlplane.decision.internal;

import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionCreator;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
class DefaultGovernanceDecisionCreator
        implements GovernanceDecisionCreator {

    @Override
    public GovernanceDecision create(
            UUID decisionId,
            UUID organizationId,
            UUID governedActionId,
            PolicyEvaluationResult evaluationResult,
            Instant decidedAt
    ) {
        Objects.requireNonNull(
                decisionId,
                "decisionId must not be null"
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
                evaluationResult,
                "evaluationResult must not be null"
        );

        Objects.requireNonNull(
                decidedAt,
                "decidedAt must not be null"
        );

        return switch (evaluationResult) {
            case PolicyEvaluationResult.Matched matched ->
                    fromMatched(
                            decisionId,
                            organizationId,
                            governedActionId,
                            matched,
                            decidedAt
                    );

            case PolicyEvaluationResult.DefaultDenied defaultDenied ->
                    fromDefaultDenied(
                            decisionId,
                            organizationId,
                            governedActionId,
                            defaultDenied,
                            decidedAt
                    );
        };
    }

    private GovernanceDecision fromMatched(
            UUID decisionId,
            UUID organizationId,
            UUID governedActionId,
            PolicyEvaluationResult.Matched matched,
            Instant decidedAt
    ) {
        return new GovernanceDecision(
                decisionId,
                organizationId,
                governedActionId,
                matched.policyVersionId(),
                matched.policyRuleId(),
                matched.outcome(),
                matched.riskScore(),
                matched.reasonCodes(),
                decidedAt
        );
    }

    private GovernanceDecision fromDefaultDenied(
            UUID decisionId,
            UUID organizationId,
            UUID governedActionId,
            PolicyEvaluationResult.DefaultDenied defaultDenied,
            Instant decidedAt
    ) {
        return new GovernanceDecision(
                decisionId,
                organizationId,
                governedActionId,
                defaultDenied.policyVersionId(),
                null,
                DecisionOutcome.DENY,
                defaultDenied.riskScore(),
                List.of(
                        defaultDenied.reasonCode()
                ),
                decidedAt
        );
    }
}