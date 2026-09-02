package com.proofmesh.controlplane.approval.internal;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestCreator;
import com.proofmesh.controlplane.approval.ApprovalRequestInsertResult;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;
import com.proofmesh.controlplane.approval.ApprovalRequestMaterializer;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.governedaction.GovernedAction;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
class DefaultApprovalRequestMaterializer
        implements ApprovalRequestMaterializer {

    private final ApprovalRequestCreator
            approvalRequestCreator;

    private final ApprovalRequestRepository
            approvalRequestRepository;

    DefaultApprovalRequestMaterializer(
            ApprovalRequestCreator approvalRequestCreator,
            ApprovalRequestRepository approvalRequestRepository
    ) {
        this.approvalRequestCreator =
                Objects.requireNonNull(
                        approvalRequestCreator,
                        "approvalRequestCreator must not be null"
                );

        this.approvalRequestRepository =
                Objects.requireNonNull(
                        approvalRequestRepository,
                        "approvalRequestRepository must not be null"
                );
    }

    @Override
    public ApprovalRequest materialize(
            GovernedAction governedAction,
            GovernanceDecision governanceDecision,
            Instant requestedAt,
            Instant expiresAt
    ) {
        Objects.requireNonNull(
                governedAction,
                "governedAction must not be null"
        );

        Objects.requireNonNull(
                governanceDecision,
                "governanceDecision must not be null"
        );

        Objects.requireNonNull(
                requestedAt,
                "requestedAt must not be null"
        );

        Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );

        ApprovalRequest candidate =
                approvalRequestCreator.create(
                        UUID.randomUUID(),
                        governedAction,
                        governanceDecision,
                        requestedAt,
                        expiresAt
                );

        ApprovalRequestInsertResult insertResult =
                approvalRequestRepository
                        .insertIfAbsent(
                                candidate
                        );

        return switch (insertResult) {
            case ApprovalRequestInsertResult.Inserted inserted ->
                    inserted.approvalRequest();

            case ApprovalRequestInsertResult.Existing existing -> {
                ApprovalRequest authoritative =
                        existing.approvalRequest();

                verifyAuthoritativeBinding(
                        candidate,
                        authoritative
                );

                yield authoritative;
            }
        };
    }

    private void verifyAuthoritativeBinding(
            ApprovalRequest candidate,
            ApprovalRequest authoritative
    ) {
        if (!candidate.organizationId()
                .equals(
                        authoritative.organizationId()
                )
                || !candidate.governedActionId()
                        .equals(
                                authoritative.governedActionId()
                        )
                || !candidate.governanceDecisionId()
                        .equals(
                                authoritative.governanceDecisionId()
                        )
                || !candidate.agentId()
                        .equals(
                                authoritative.agentId()
                        )
                || !candidate.toolName()
                        .equals(
                                authoritative.toolName()
                        )
                || !candidate.operationName()
                        .equals(
                                authoritative.operationName()
                        )
                || !candidate.requestPayloadHash()
                        .equals(
                                authoritative.requestPayloadHash()
                        )) {

            throw new ApprovalRequestIntegrityException(
                    "existing approval request does not match authoritative governance binding"
            );
        }
    }
}