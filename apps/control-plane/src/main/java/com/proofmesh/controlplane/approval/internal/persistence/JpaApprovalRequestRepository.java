package com.proofmesh.controlplane.approval.internal.persistence;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestInsertResult;
import com.proofmesh.controlplane.approval.ApprovalRequestIntegrityException;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaApprovalRequestRepository
        implements ApprovalRequestRepository {

    private final SpringDataApprovalRequestJpaRepository
            springDataRepository;

    private final ApprovalRequestJpaMapper mapper;

    JpaApprovalRequestRepository(
            SpringDataApprovalRequestJpaRepository
                    springDataRepository,
            ApprovalRequestJpaMapper mapper
    ) {
        this.springDataRepository =
                Objects.requireNonNull(
                        springDataRepository,
                        "springDataRepository must not be null"
                );

        this.mapper =
                Objects.requireNonNull(
                        mapper,
                        "mapper must not be null"
                );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApprovalRequest>
            findByOrganizationIdAndId(
                    UUID organizationId,
                    UUID approvalRequestId
            ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                approvalRequestId,
                "approvalRequestId must not be null"
        );

        return springDataRepository
                .findByOrganizationIdAndId(
                        organizationId,
                        approvalRequestId
                )
                .map(
                        mapper::toDomain
                );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApprovalRequest>
            findByOrganizationIdAndGovernanceDecisionId(
                    UUID organizationId,
                    UUID governanceDecisionId
            ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                governanceDecisionId,
                "governanceDecisionId must not be null"
        );

        return springDataRepository
                .findByOrganizationIdAndGovernanceDecisionId(
                        organizationId,
                        governanceDecisionId
                )
                .map(
                        mapper::toDomain
                );
    }

    @Override
    @Transactional
    public ApprovalRequestInsertResult insertIfAbsent(
            ApprovalRequest approvalRequest
    ) {
        Objects.requireNonNull(
                approvalRequest,
                "approvalRequest must not be null"
        );

        if (!approvalRequest.isPending()) {
            throw new IllegalArgumentException(
                    "only pending approval requests can be inserted"
            );
        }

        int affectedRows =
                springDataRepository
                        .insertIfAbsent(
                                approvalRequest.id(),
                                approvalRequest.organizationId(),
                                approvalRequest.governedActionId(),
                                approvalRequest.governanceDecisionId(),
                                approvalRequest.agentId(),
                                approvalRequest
                                        .toolName()
                                        .value(),
                                approvalRequest
                                        .operationName()
                                        .value(),
                                approvalRequest
                                        .requestPayloadHash()
                                        .value(),
                                approvalRequest.requestedAt(),
                                approvalRequest.expiresAt()
                        );

        if (affectedRows == 1) {
            return new ApprovalRequestInsertResult
                    .Inserted(
                            approvalRequest
                    );
        }

        if (affectedRows == 0) {
            ApprovalRequest existing =
                    springDataRepository
                            .findByOrganizationIdAndGovernanceDecisionId(
                                    approvalRequest.organizationId(),
                                    approvalRequest.governanceDecisionId()
                            )
                            .map(
                                    mapper::toDomain
                            )
                            .orElseThrow(
                                    () ->
                                            new ApprovalRequestIntegrityException(
                                                    "approval request conflict occurred but the authoritative request could not be loaded"
                                            )
                            );

            return new ApprovalRequestInsertResult
                    .Existing(
                            existing
                    );
        }

        throw new ApprovalRequestIntegrityException(
                "unexpected approval request insert row count: "
                        + affectedRows
        );
    }
}