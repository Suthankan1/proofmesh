package com.proofmesh.controlplane.risk.internal;

import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentRequest;
import com.proofmesh.controlplane.risk.RiskAssessor;
import com.proofmesh.controlplane.risk.RiskLogicVersion;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
class DefaultRiskAssessor
        implements RiskAssessor {

    private static final RiskLogicVersion LOGIC_VERSION =
            new RiskLogicVersion(
                    "deterministic-v1"
            );

    private static final RiskSignal NO_RISK_METADATA_SIGNAL =
            new RiskSignal(
                    new RiskSignalCode(
                            "UNCLASSIFIED_OPERATION"
                    ),
                    RiskSeverity.CRITICAL,
                    100,
                    "No deterministic risk metadata was available for the governed action."
            );

    @Override
    public RiskAssessment assess(
            RiskAssessmentRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        List<RiskSignal> effectiveSignals =
                resolveEffectiveSignals(
                        request.signals()
                );

        int score =
                calculateScore(
                        effectiveSignals
                );

        return new RiskAssessment(
                request.assessmentId(),
                request.organizationId(),
                request.governedActionId(),
                LOGIC_VERSION,
                new RiskScore(
                        score
                ),
                effectiveSignals,
                request.assessedAt()
        );
    }

    private List<RiskSignal> resolveEffectiveSignals(
            List<RiskSignal> suppliedSignals
    ) {
        if (suppliedSignals.isEmpty()) {
            return List.of(
                    NO_RISK_METADATA_SIGNAL
            );
        }

        List<RiskSignal> ordered =
                new ArrayList<>(
                        suppliedSignals
                );

        ordered.sort(
                Comparator.comparing(
                        signal ->
                                signal.code().value()
                )
        );

        return List.copyOf(
                ordered
        );
    }

    private int calculateScore(
            List<RiskSignal> signals
    ) {
        int total = 0;

        for (RiskSignal signal : signals) {
            total =
                    Math.min(
                            100,
                            total + signal.weight()
                    );

            if (total == 100) {
                break;
            }
        }

        return total;
    }
}