package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentConflictException;
import com.proofmesh.controlplane.risk.RiskAssessmentRecorder;
import com.proofmesh.controlplane.risk.RiskAssessmentRequest;
import com.proofmesh.controlplane.risk.RiskAssessor;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class DefaultRuntimeRiskAssessmentResolver
        implements RuntimeRiskAssessmentResolver {

    private final RiskAssessor riskAssessor;

    private final RiskAssessmentRecorder riskAssessmentRecorder;

    DefaultRuntimeRiskAssessmentResolver(
            RiskAssessor riskAssessor,
            RiskAssessmentRecorder riskAssessmentRecorder
    ) {
        this.riskAssessor =
                Objects.requireNonNull(
                        riskAssessor,
                        "riskAssessor must not be null"
                );

        this.riskAssessmentRecorder =
                Objects.requireNonNull(
                        riskAssessmentRecorder,
                        "riskAssessmentRecorder must not be null"
                );
    }

    @Override
    public RuntimeRiskAssessmentResolution resolve(
            RuntimeGovernanceRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        GovernedAction governedAction =
                request.governedAction();

        RiskAssessmentRequest assessmentRequest =
                new RiskAssessmentRequest(
                        request.riskAssessmentId(),
                        governedAction.organizationId(),
                        governedAction.id(),
                        request.riskSignals(),
                        request.evaluatedAt()
                );

        RiskAssessment proposedAssessment =
                riskAssessor.assess(
                        assessmentRequest
                );

        try {
            RiskAssessment authoritativeAssessment =
                    riskAssessmentRecorder.record(
                            proposedAssessment
                    );

            return new RuntimeRiskAssessmentResolution.Ready(
                    authoritativeAssessment
            );
        } catch (RiskAssessmentConflictException exception) {
            return new RuntimeRiskAssessmentResolution.Failed(
                    RuntimeGovernanceFailureReason
                            .RISK_ASSESSMENT_CONFLICT
            );
        }
    }
}