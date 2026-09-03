package com.proofmesh.controlplane.executiongrant.internal;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantAuthorizationContext;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class DefaultExecutionGrantAuthorizationContextResolver
        implements ExecutionGrantAuthorizationContextResolver {

    private final GovernedActionRepository actionRepository;
    private final GovernanceDecisionRepository decisionRepository;
    private final ApprovalRequestRepository approvalRepository;

    DefaultExecutionGrantAuthorizationContextResolver(
            GovernedActionRepository actionRepository,
            GovernanceDecisionRepository decisionRepository,
            ApprovalRequestRepository approvalRepository
    ) {
        this.actionRepository = Objects.requireNonNull(
                actionRepository,
                "actionRepository must not be null"
        );
        this.decisionRepository = Objects.requireNonNull(
                decisionRepository,
                "decisionRepository must not be null"
        );
        this.approvalRepository = Objects.requireNonNull(
                approvalRepository,
                "approvalRepository must not be null"
        );
    }

    @Override
    public Optional<ExecutionGrantAuthorizationContext> resolve(
            UUID organizationId,
            UUID governedActionId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );
        Objects.requireNonNull(
                governedActionId,
                "governedActionId must not be null"
        );

        Optional<GovernedAction> actionOpt =
                actionRepository.findByIdAndOrganizationId(
                        governedActionId,
                        organizationId
                );
        if (actionOpt.isEmpty()) {
            return Optional.empty();
        }

        Optional<GovernanceDecision> decisionOpt =
                decisionRepository.findByOrganizationIdAndGovernedActionId(
                        organizationId,
                        governedActionId
                );
        if (decisionOpt.isEmpty()) {
            return Optional.empty();
        }

        GovernedAction action = actionOpt.get();
        GovernanceDecision decision = decisionOpt.get();

        Optional<ApprovalRequest> approvalOpt;
        if (decision.requiresApproval()) {
            approvalOpt = approvalRepository.findByOrganizationIdAndGovernanceDecisionId(
                    organizationId,
                    decision.id()
            );
        } else {
            approvalOpt = Optional.empty();
        }

        return Optional.of(
                new ExecutionGrantAuthorizationContext(
                        action,
                        decision,
                        approvalOpt
                )
        );
    }
}
