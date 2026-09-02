package com.proofmesh.controlplane.approval;

import java.time.Instant;
import java.util.UUID;

public interface ApprovalRequestExpiryStore {

    boolean expirePending(
            UUID organizationId,
            UUID approvalRequestId,
            Instant expiredAt
    );
}