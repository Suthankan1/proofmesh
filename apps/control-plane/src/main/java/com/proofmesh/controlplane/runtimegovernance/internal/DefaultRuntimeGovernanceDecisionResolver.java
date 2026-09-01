package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecorder;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecordingConflictException;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class DefaultRuntimeGovernanceDecisionResolver
        implements RuntimeGovernanceDecisionResolver {

    private final GovernanceDecisionRecorder
            governanceDecisionRecorder;

    DefaultRuntimeGovernanceDecisionResolver(
            GovernanceDecisionRecorder governanceDecisionRecorder
    ) {
        this.governanceDecisionRecorder =
                Objects.requireNonNull(
                        governanceDecisionRecorder,
                        "governanceDecisionRecorder must not be null"
                );
    }

    @Override
    public RuntimeGovernanceDecisionResolution resolve(
            RuntimeGovernanceContext context,
            RuntimeGovernanceRequest request,
            RiskAssessment riskAssessment,
            PolicyEvaluationResult evaluationResult
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

        Objects.requireNonNull(
                evaluationResult,
                "evaluationResult must not be null"
        );

        GovernedAction governedAction =
                request.governedAction();

        validateProvenance(
                context,
                governedAction,
                riskAssessment,
                evaluationResult
        );

        try {
            GovernanceDecision authoritativeDecision =
                    governanceDecisionRecorder
                            .recordAuthoritativeDecision(
                                    request.governanceDecisionId(),
                                    governedAction.organizationId(),
                                    governedAction.id(),
                                    evaluationResult,
                                    request.evaluatedAt()
                            );

            validateAuthoritativeDecision(
                    context,
                    governedAction,
                    riskAssessment,
                    authoritativeDecision
            );

            return new RuntimeGovernanceDecisionResolution.Ready(
                    authoritativeDecision
            );
        } catch (
                GovernanceDecisionRecordingConflictException exception
        ) {
            return new RuntimeGovernanceDecisionResolution.Failed(
                    RuntimeGovernanceFailureReason
                            .GOVERNANCE_DECISION_CONFLICT
            );
        }
    }

    private void validateProvenance(
            RuntimeGovernanceContext context,
            GovernedAction governedAction,
            RiskAssessment riskAssessment,
            PolicyEvaluationResult evaluationResult
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

        if (!evaluationPolicyVersionId(
                evaluationResult
        ).equals(
                context.policyVersion().id()
        )) {
            throw new IllegalArgumentException(
                    "policy evaluation and runtime policy version must match"
            );
        }

        if (!evaluationRiskScore(
                evaluationResult
        ).equals(
                riskAssessment.riskScore()
        )) {
            throw new IllegalArgumentException(
                    "policy evaluation and risk assessment score must match"
            );
        }
    }

    private void validateAuthoritativeDecision(
            RuntimeGovernanceContext context,
            GovernedAction governedAction,
            RiskAssessment riskAssessment,
            GovernanceDecision decision
    ) {
        if (!decision.organizationId()
                .equals(
                        governedAction.organizationId()
                )) {
            throw new IllegalStateException(
                    "authoritative decision organization does not match governed action"
            );
        }

        if (!decision.governedActionId()
                .equals(
                        governedAction.id()
                )) {
            throw new IllegalStateException(
                    "authoritative decision does not reference governed action"
            );
        }

        if (!decision.policyVersionId()
                .equals(
                        context.policyVersion().id()
                )) {
            throw new IllegalStateException(
                    "authoritative decision policy version does not match runtime context"
            );
        }

        if (!decision.riskScore()
                .equals(
                        riskAssessment.riskScore()
                )) {
            throw new IllegalStateException(
                    "authoritative decision risk score does not match authoritative assessment"
            );
        }
    }

    private PolicyVersionId evaluationPolicyVersionId(
            PolicyEvaluationResult evaluationResult
    ) {
        return switch (evaluationResult) {
            case PolicyEvaluationResult.Matched matched ->
                    matched.policyVersionId();

            case PolicyEvaluationResult.DefaultDenied denied ->
                    denied.policyVersionId();
        };
    }

    private RiskScore evaluationRiskScore(
            PolicyEvaluationResult evaluationResult
    ) {
        return switch (evaluationResult) {
            case PolicyEvaluationResult.Matched matched ->
                    matched.riskScore();

            case PolicyEvaluationResult.DefaultDenied denied ->
                    denied.riskScore();
        };
    }
}