package com.proofmesh.controlplane.executiongrant;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.governedaction.GovernedAction;

import java.util.Objects;
import java.util.Optional;

/**
 * Structural authorization inputs for execution-grant eligibility and claims preparation.
 *
 * <p>This record encapsulates the domain facts required to evaluate and prepare an execution grant:
 * the governed action, the governance decision, and an optional approval request.
 * Public construction of this context represents structural facts only and does not prove
 * authoritative persistence; authoritative state resolution is owned by callers.</p>
 */
public record ExecutionGrantAuthorizationContext(
        GovernedAction governedAction,
        GovernanceDecision governanceDecision,
        Optional<ApprovalRequest> approvalRequest
) {

    public ExecutionGrantAuthorizationContext {
        Objects.requireNonNull(
                governedAction,
                "governedAction must not be null"
        );

        Objects.requireNonNull(
                governanceDecision,
                "governanceDecision must not be null"
        );

        Objects.requireNonNull(
                approvalRequest,
                "approvalRequest must not be null"
        );
    }
}
