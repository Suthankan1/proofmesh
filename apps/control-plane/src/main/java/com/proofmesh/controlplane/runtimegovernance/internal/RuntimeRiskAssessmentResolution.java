package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;

import java.util.Objects;

sealed interface RuntimeRiskAssessmentResolution
        permits RuntimeRiskAssessmentResolution.Ready,
                RuntimeRiskAssessmentResolution.Failed {

    record Ready(
            RiskAssessment riskAssessment
    ) implements RuntimeRiskAssessmentResolution {

        public Ready {
            Objects.requireNonNull(
                    riskAssessment,
                    "riskAssessment must not be null"
            );
        }
    }

    record Failed(
            RuntimeGovernanceFailureReason reason
    ) implements RuntimeRiskAssessmentResolution {

        public Failed {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }
}