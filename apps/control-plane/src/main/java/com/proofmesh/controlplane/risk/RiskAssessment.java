package com.proofmesh.controlplane.risk;

import com.proofmesh.controlplane.decision.RiskScore;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RiskAssessment(
        UUID id,
        UUID organizationId,
        UUID governedActionId,
        RiskLogicVersion logicVersion,
        RiskScore riskScore,
        List<RiskSignal> signals,
        Instant assessedAt
) {

    public RiskAssessment {
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
                logicVersion,
                "logicVersion must not be null"
        );

        Objects.requireNonNull(
                riskScore,
                "riskScore must not be null"
        );

        Objects.requireNonNull(
                signals,
                "signals must not be null"
        );

        Objects.requireNonNull(
                assessedAt,
                "assessedAt must not be null"
        );

        if (signals.isEmpty()) {
            throw new IllegalArgumentException(
                    "risk assessment must contain at least one signal"
            );
        }

        if (signals.stream()
                .anyMatch(
                        Objects::isNull
                )) {
            throw new IllegalArgumentException(
                    "risk assessment signals must not contain null"
            );
        }

        Set<RiskSignalCode> signalCodes =
                new HashSet<>();

        for (RiskSignal signal : signals) {
            if (!signalCodes.add(
                    signal.code()
            )) {
                throw new IllegalArgumentException(
                        "risk assessment signal codes must be unique"
                );
            }
        }

        signals =
                List.copyOf(
                        signals
                );
    }

    public boolean hasSameAssessmentSemanticsAs(
            RiskAssessment other
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
                && logicVersion.equals(
                        other.logicVersion
                )
                && riskScore.equals(
                        other.riskScore
                )
                && signals.equals(
                        other.signals
                );
    }
}