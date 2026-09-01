package com.proofmesh.controlplane.risk;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RiskAssessmentRequest(
        UUID assessmentId,
        UUID organizationId,
        UUID governedActionId,
        List<RiskSignal> signals,
        Instant assessedAt
) {

    public RiskAssessmentRequest {
        Objects.requireNonNull(
                assessmentId,
                "assessmentId must not be null"
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
                signals,
                "signals must not be null"
        );

        Objects.requireNonNull(
                assessedAt,
                "assessedAt must not be null"
        );

        if (signals.stream().anyMatch(
                Objects::isNull
        )) {
            throw new IllegalArgumentException(
                    "risk assessment request signals must not contain null"
            );
        }

        signals =
                List.copyOf(
                        signals
                );
    }
}