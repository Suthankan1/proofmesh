package com.proofmesh.controlplane.runtimegovernance;

import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.risk.RiskAssessment;

import java.util.Objects;
import java.util.UUID;

public sealed interface RuntimeGovernanceResult
        permits RuntimeGovernanceResult.Governed,
                RuntimeGovernanceResult.FailedClosed {

    record Governed(
            AgentPolicyBinding policyBinding,
            RiskAssessment riskAssessment,
            GovernanceDecision decision
    ) implements RuntimeGovernanceResult {

        public Governed {
            Objects.requireNonNull(
                    policyBinding,
                    "policyBinding must not be null"
            );

            Objects.requireNonNull(
                    riskAssessment,
                    "riskAssessment must not be null"
            );

            Objects.requireNonNull(
                    decision,
                    "decision must not be null"
            );

            if (!policyBinding.organizationId()
                    .equals(
                            riskAssessment.organizationId()
                    )) {
                throw new IllegalArgumentException(
                        "policy binding and risk assessment organization must match"
                );
            }

            if (!riskAssessment.organizationId()
                    .equals(
                            decision.organizationId()
                    )) {
                throw new IllegalArgumentException(
                        "risk assessment and governance decision organization must match"
                );
            }

            if (!riskAssessment.governedActionId()
                    .equals(
                            decision.governedActionId()
                    )) {
                throw new IllegalArgumentException(
                        "risk assessment and governance decision action must match"
                );
            }

            if (!policyBinding.policyVersionId()
                    .equals(
                            decision.policyVersionId()
                    )) {
                throw new IllegalArgumentException(
                        "policy binding and governance decision policy version must match"
                );
            }

            if (!riskAssessment.riskScore()
                    .equals(
                            decision.riskScore()
                    )) {
                throw new IllegalArgumentException(
                        "risk assessment and governance decision risk score must match"
                );
            }
        }
    }

    record FailedClosed(
            UUID organizationId,
            UUID agentId,
            UUID governedActionId,
            RuntimeGovernanceFailureReason reason
    ) implements RuntimeGovernanceResult {

        public FailedClosed {
            Objects.requireNonNull(
                    organizationId,
                    "organizationId must not be null"
            );

            Objects.requireNonNull(
                    agentId,
                    "agentId must not be null"
            );

            Objects.requireNonNull(
                    governedActionId,
                    "governedActionId must not be null"
            );

            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }
}