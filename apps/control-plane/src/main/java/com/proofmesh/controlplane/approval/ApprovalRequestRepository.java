package com.proofmesh.controlplane.approval;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository {

    Optional<ApprovalRequest> findByOrganizationIdAndId(
            UUID organizationId,
            UUID approvalRequestId
    );

    Optional<ApprovalRequest>
            findByOrganizationIdAndGovernanceDecisionId(
                    UUID organizationId,
                    UUID governanceDecisionId
            );

    ApprovalRequestInsertResult insertIfAbsent(
            ApprovalRequest approvalRequest
    );
}