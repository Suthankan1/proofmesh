package com.proofmesh.controlplane.approval;

import java.time.Instant;
import java.util.UUID;

public interface ApprovalRequestExpirer {

    ApprovalRequest expire(
            UUID organizationId,
            UUID approvalRequestId,
            Instant expiredAt
    );
}