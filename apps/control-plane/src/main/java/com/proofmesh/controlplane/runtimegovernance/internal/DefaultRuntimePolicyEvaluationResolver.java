package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.PolicyEvaluator;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class DefaultRuntimePolicyEvaluationResolver
        implements RuntimePolicyEvaluationResolver {

    private final PolicyEvaluator policyEvaluator;

    DefaultRuntimePolicyEvaluationResolver(
            PolicyEvaluator policyEvaluator
    ) {
        this.policyEvaluator =
                Objects.requireNonNull(
                        policyEvaluator,
                        "policyEvaluator must not be null"
                );
    }

    @Override
    public PolicyEvaluationResult evaluate(
            RuntimeGovernanceContext context,
            RuntimeGovernanceRequest request,
            RiskAssessment riskAssessment
    ) {
        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        Objects.requireNonNull(
                riskAssessment,
                "riskAssessment must not be null"
        );

        GovernedAction governedAction =
                request.governedAction();

        validateConsistency(
                context,
                governedAction,
                riskAssessment
        );

        return policyEvaluator.evaluate(
                context.policyVersion(),
                governedAction,
                riskAssessment.riskScore()
        );
    }

    private void validateConsistency(
            RuntimeGovernanceContext context,
            GovernedAction governedAction,
            RiskAssessment riskAssessment
    ) {
        if (!governedAction.organizationId()
                .equals(
                        context.agent().organizationId()
                )) {
            throw new IllegalArgumentException(
                    "governed action and runtime context organization must match"
            );
        }

        if (!governedAction.agentId()
                .equals(
                        context.agent().id()
                )) {
            throw new IllegalArgumentException(
                    "governed action and runtime context agent must match"
            );
        }

        if (!riskAssessment.organizationId()
                .equals(
                        governedAction.organizationId()
                )) {
            throw new IllegalArgumentException(
                    "risk assessment and governed action organization must match"
            );
        }

        if (!riskAssessment.governedActionId()
                .equals(
                        governedAction.id()
                )) {
            throw new IllegalArgumentException(
                    "risk assessment and governed action identity must match"
            );
        }
    }
}