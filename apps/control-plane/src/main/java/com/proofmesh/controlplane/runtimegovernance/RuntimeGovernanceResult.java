package com.proofmesh.controlplane.runtimegovernance;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.risk.RiskAssessment;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public sealed interface RuntimeGovernanceResult
        permits RuntimeGovernanceResult.Governed,
                RuntimeGovernanceResult.FailedClosed {

    record Governed(
            AgentPolicyBinding policyBinding,
            RiskAssessment riskAssessment,
            GovernanceDecision decision,
            Optional<ApprovalRequest> approvalRequest
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

            Objects.requireNonNull(
                    approvalRequest,
                    "approvalRequest must not be null"
            );

            if (decision.requiresApproval()
                    != approvalRequest.isPresent()) {
                throw new IllegalArgumentException(
                        "REQUIRE_APPROVAL governance decisions must have an approval request, and other outcomes must not"
                );
            }

            if (!policyBinding.organizationId()
                    .equals(
                            riskAssessment.organizationId()
                    )) {
                throw new IllegalArgumentException(
                        "policy binding and risk assessment must belong to the same organization"
                );
            }

            if (!policyBinding.organizationId()
                    .equals(
                            decision.organizationId()
                    )) {
                throw new IllegalArgumentException(
                        "policy binding and governance decision must belong to the same organization"
                );
            }

            if (!riskAssessment.governedActionId()
                    .equals(
                            decision.governedActionId()
                    )) {
                throw new IllegalArgumentException(
                        "risk assessment and governance decision must reference the same governed action"
                );
            }

            if (!policyBinding.policyVersionId()
                    .equals(
                            decision.policyVersionId()
                    )) {
                throw new IllegalArgumentException(
                        "governance decision must reference the bound policy version"
                );
            }

            if (!riskAssessment.riskScore()
                    .equals(
                            decision.riskScore()
                    )) {
                throw new IllegalArgumentException(
                        "governance decision risk score must match the authoritative risk assessment"
                );
            }

            approvalRequest.ifPresent(
                    approval -> {
                        if (!approval.organizationId()
                                .equals(
                                        decision.organizationId()
                                )) {
                            throw new IllegalArgumentException(
                                    "approval request and governance decision must belong to the same organization"
                            );
                        }

                        if (!approval.governedActionId()
                                .equals(
                                        decision.governedActionId()
                                )) {
                            throw new IllegalArgumentException(
                                    "approval request must reference the governed action"
                            );
                        }

                        if (!approval.governanceDecisionId()
                                .equals(
                                        decision.id()
                                )) {
                            throw new IllegalArgumentException(
                                    "approval request must reference the governance decision"
                            );
                        }

                        if (!approval.agentId()
                                .equals(
                                        policyBinding.agentId()
                                )) {
                            throw new IllegalArgumentException(
                                    "approval request must reference the bound agent"
                            );
                        }
                    }
            );
        }

        public Governed(
                AgentPolicyBinding policyBinding,
                RiskAssessment riskAssessment,
                GovernanceDecision decision
        ) {
            this(
                    policyBinding,
                    riskAssessment,
                    decision,
                    Optional.empty()
            );
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