package com.proofmesh.controlplane.executiongrant;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceResult;

import java.time.Instant;
import java.util.Objects;

public class ExecutionGrantEligibilityEvaluator {

    public ExecutionGrantEligibility evaluate(
            GovernedAction governedAction,
            RuntimeGovernanceResult runtimeResult,
            Instant now
    ) {
        Objects.requireNonNull(
                governedAction,
                "governedAction must not be null"
        );

        Objects.requireNonNull(
                runtimeResult,
                "runtimeResult must not be null"
        );

        Objects.requireNonNull(
                now,
                "now must not be null"
        );

        return switch (runtimeResult) {
            case RuntimeGovernanceResult.FailedClosed ignored ->
                    ineligible(
                            ExecutionGrantEligibility.Reason
                                    .FAILED_CLOSED
                    );

            case RuntimeGovernanceResult.Governed governed ->
                    evaluateGoverned(
                            governedAction,
                            governed,
                            now
                    );
        };
    }

    private ExecutionGrantEligibility evaluateGoverned(
            GovernedAction governedAction,
            RuntimeGovernanceResult.Governed governed,
            Instant now
    ) {
        if (!matchesActionProvenance(
                governedAction,
                governed
        )) {
            return ineligible(
                    ExecutionGrantEligibility.Reason
                            .ACTION_PROVENANCE_MISMATCH
            );
        }

        GovernanceDecision decision =
                governed.decision();

        return switch (decision.outcome()) {
            case DENY ->
                    ineligible(
                            ExecutionGrantEligibility.Reason
                                    .DECISION_DENIED
                    );

            case ALLOW ->
                    new ExecutionGrantEligibility.Eligible();

            case REQUIRE_APPROVAL ->
                    evaluateApproval(
                            governedAction,
                            governed,
                            now
                    );
        };
    }

    private boolean matchesActionProvenance(
            GovernedAction governedAction,
            RuntimeGovernanceResult.Governed governed
    ) {
        GovernanceDecision decision =
                governed.decision();

        return governedAction.organizationId()
                .equals(
                        decision.organizationId()
                )
                && governedAction.id()
                        .equals(
                                decision.governedActionId()
                        )
                && governedAction.organizationId()
                        .equals(
                                governed.policyBinding()
                                        .organizationId()
                        )
                && governedAction.agentId()
                        .equals(
                                governed.policyBinding()
                                        .agentId()
                        );
    }

    private ExecutionGrantEligibility evaluateApproval(
            GovernedAction governedAction,
            RuntimeGovernanceResult.Governed governed,
            Instant now
    ) {
        ApprovalRequest approvalRequest =
                governed.approvalRequest()
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "REQUIRE_APPROVAL governance result must have an approval request"
                                )
                        );

        if (!approvalRequest.isApprovedAndValidAt(
                now
        )) {
            return ineligible(
                    ExecutionGrantEligibility.Reason
                            .APPROVAL_NOT_CURRENTLY_VALID
            );
        }

        if (!matchesApprovalProvenance(
                governedAction,
                approvalRequest
        )) {
            return ineligible(
                    ExecutionGrantEligibility.Reason
                            .APPROVAL_PROVENANCE_MISMATCH
            );
        }

        return new ExecutionGrantEligibility.Eligible();
    }

    private boolean matchesApprovalProvenance(
            GovernedAction governedAction,
            ApprovalRequest approvalRequest
    ) {
        return approvalRequest.toolName()
                .equals(
                        governedAction.toolName()
                )
                && approvalRequest.operationName()
                        .equals(
                                governedAction.operationName()
                        )
                && approvalRequest.requestPayloadHash()
                        .equals(
                                governedAction.requestPayloadHash()
                        );
    }

    private ExecutionGrantEligibility.Ineligible ineligible(
            ExecutionGrantEligibility.Reason reason
    ) {
        return new ExecutionGrantEligibility.Ineligible(
                reason
        );
    }
}
