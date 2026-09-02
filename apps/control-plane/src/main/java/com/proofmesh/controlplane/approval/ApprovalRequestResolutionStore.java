package com.proofmesh.controlplane.approval;

import java.time.Instant;
import java.util.UUID;

public interface ApprovalRequestResolutionStore {

    boolean approvePending(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    );

    boolean rejectPending(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    );
}