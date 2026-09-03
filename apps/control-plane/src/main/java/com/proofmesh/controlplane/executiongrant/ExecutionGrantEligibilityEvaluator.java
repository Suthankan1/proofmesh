package com.proofmesh.controlplane.executiongrant;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceResult;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class ExecutionGrantEligibilityEvaluator {

    public ExecutionGrantEligibility evaluate(
            ExecutionGrantAuthorizationContext context,
            Instant now
    ) {
        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        Objects.requireNonNull(
                now,
                "now must not be null"
        );

        GovernedAction action =
                context.governedAction();
        GovernanceDecision decision =
                context.governanceDecision();

        if (!matchesActionProvenance(
                action,
                decision
        )) {
            return ineligible(
                    ExecutionGrantEligibility.Reason
                            .ACTION_PROVENANCE_MISMATCH
            );
        }

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
                            action,
                            decision,
                            context.approvalRequest(),
                            now
                    );
        };
    }

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
        if (!matchesLegacyPolicyBindingProvenance(
                governedAction,
                governed
        )) {
            return ineligible(
                    ExecutionGrantEligibility.Reason
                            .ACTION_PROVENANCE_MISMATCH
            );
        }

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction,
                        governed.decision(),
                        governed.approvalRequest()
                );

        return evaluate(
                context,
                now
        );
    }

    private boolean matchesLegacyPolicyBindingProvenance(
            GovernedAction governedAction,
            RuntimeGovernanceResult.Governed governed
    ) {
        return governedAction.organizationId()
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

    private boolean matchesActionProvenance(
            GovernedAction action,
            GovernanceDecision decision
    ) {
        return action.organizationId()
                .equals(
                        decision.organizationId()
                )
                && action.id()
                        .equals(
                                decision.governedActionId()
                        );
    }

    private ExecutionGrantEligibility evaluateApproval(
            GovernedAction action,
            GovernanceDecision decision,
            Optional<ApprovalRequest> approvalRequestOpt,
            Instant now
    ) {
        if (approvalRequestOpt.isEmpty()) {
            return ineligible(
                    ExecutionGrantEligibility.Reason
                            .APPROVAL_NOT_CURRENTLY_VALID
            );
        }

        ApprovalRequest approvalRequest =
                approvalRequestOpt.get();

        if (!approvalRequest.isApprovedAndValidAt(
                now
        )) {
            return ineligible(
                    ExecutionGrantEligibility.Reason
                            .APPROVAL_NOT_CURRENTLY_VALID
            );
        }

        if (!matchesApprovalProvenance(
                action,
                decision,
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
            GovernedAction action,
            GovernanceDecision decision,
            ApprovalRequest approval
    ) {
        return approval.organizationId()
                .equals(
                        action.organizationId()
                )
                && approval.organizationId()
                        .equals(
                                decision.organizationId()
                        )
                && approval.agentId()
                        .equals(
                                action.agentId()
                        )
                && approval.governedActionId()
                        .equals(
                                action.id()
                        )
                && approval.governedActionId()
                        .equals(
                                decision.governedActionId()
                        )
                && approval.governanceDecisionId()
                        .equals(
                                decision.id()
                        )
                && approval.toolName()
                        .equals(
                                action.toolName()
                        )
                && approval.operationName()
                        .equals(
                                action.operationName()
                        )
                && approval.requestPayloadHash()
                        .equals(
                                action.requestPayloadHash()
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
