package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceOrchestrator;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceResult;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
class DefaultRuntimeGovernanceOrchestrator
        implements RuntimeGovernanceOrchestrator {

    private final RuntimeGovernanceContextResolver
            contextResolver;

    private final RuntimeRiskAssessmentResolver
            riskAssessmentResolver;

    private final RuntimePolicyEvaluationResolver
            policyEvaluationResolver;

    private final RuntimeGovernanceDecisionResolver
            governanceDecisionResolver;

    DefaultRuntimeGovernanceOrchestrator(
            RuntimeGovernanceContextResolver contextResolver,
            RuntimeRiskAssessmentResolver riskAssessmentResolver,
            RuntimePolicyEvaluationResolver policyEvaluationResolver,
            RuntimeGovernanceDecisionResolver governanceDecisionResolver
    ) {
        this.contextResolver =
                Objects.requireNonNull(
                        contextResolver,
                        "contextResolver must not be null"
                );

        this.riskAssessmentResolver =
                Objects.requireNonNull(
                        riskAssessmentResolver,
                        "riskAssessmentResolver must not be null"
                );

        this.policyEvaluationResolver =
                Objects.requireNonNull(
                        policyEvaluationResolver,
                        "policyEvaluationResolver must not be null"
                );

        this.governanceDecisionResolver =
                Objects.requireNonNull(
                        governanceDecisionResolver,
                        "governanceDecisionResolver must not be null"
                );
    }

    @Override
    public RuntimeGovernanceResult govern(
            RuntimeGovernanceRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        RuntimeGovernanceContextResolution contextResolution =
                contextResolver.resolve(
                        request
                );

        if (contextResolution
                instanceof RuntimeGovernanceContextResolution.Failed failed) {
            return failedClosed(
                    request,
                    failed.reason()
            );
        }

        RuntimeGovernanceContext context =
                ((RuntimeGovernanceContextResolution.Ready)
                        contextResolution)
                        .context();

        RuntimeRiskAssessmentResolution riskResolution =
                riskAssessmentResolver.resolve(
                        request
                );

        if (riskResolution
                instanceof RuntimeRiskAssessmentResolution.Failed failed) {
            return failedClosed(
                    request,
                    failed.reason()
            );
        }

        RiskAssessment riskAssessment =
                ((RuntimeRiskAssessmentResolution.Ready)
                        riskResolution)
                        .riskAssessment();

        PolicyEvaluationResult evaluationResult =
                policyEvaluationResolver.evaluate(
                        context,
                        request,
                        riskAssessment
                );

        RuntimeGovernanceDecisionResolution decisionResolution =
                governanceDecisionResolver.resolve(
                        context,
                        request,
                        riskAssessment,
                        evaluationResult
                );

        if (decisionResolution
                instanceof RuntimeGovernanceDecisionResolution.Failed failed) {
            return failedClosed(
                    request,
                    failed.reason()
            );
        }

        return new RuntimeGovernanceResult.Governed(
                context.policyBinding(),
                riskAssessment,
                ((RuntimeGovernanceDecisionResolution.Ready)
                        decisionResolution)
                        .decision()
        );
    }

    private RuntimeGovernanceResult.FailedClosed failedClosed(
            RuntimeGovernanceRequest request,
            com.proofmesh.controlplane.runtimegovernance
                    .RuntimeGovernanceFailureReason reason
    ) {
        GovernedAction governedAction =
                request.governedAction();

        return new RuntimeGovernanceResult.FailedClosed(
                governedAction.organizationId(),
                governedAction.agentId(),
                governedAction.id(),
                reason
        );
    }
}