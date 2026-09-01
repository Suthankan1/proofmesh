package com.proofmesh.controlplane.runtimegovernance;

import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.risk.RiskSignal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RuntimeGovernanceRequest(
        UUID riskAssessmentId,
        UUID governanceDecisionId,
        GovernedAction governedAction,
        List<RiskSignal> riskSignals,
        Instant evaluatedAt
) {

    public RuntimeGovernanceRequest {
        Objects.requireNonNull(
                riskAssessmentId,
                "riskAssessmentId must not be null"
        );

        Objects.requireNonNull(
                governanceDecisionId,
                "governanceDecisionId must not be null"
        );

        Objects.requireNonNull(
                governedAction,
                "governedAction must not be null"
        );

        Objects.requireNonNull(
                riskSignals,
                "riskSignals must not be null"
        );

        Objects.requireNonNull(
                evaluatedAt,
                "evaluatedAt must not be null"
        );

        if (riskSignals.stream()
                .anyMatch(
                        Objects::isNull
                )) {
            throw new IllegalArgumentException(
                    "riskSignals must not contain null"
            );
        }

        riskSignals =
                List.copyOf(
                        riskSignals
                );
    }
}