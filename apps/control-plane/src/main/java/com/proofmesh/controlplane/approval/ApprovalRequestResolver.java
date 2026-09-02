package com.proofmesh.controlplane.approval;

import java.time.Instant;
import java.util.UUID;

public interface ApprovalRequestResolver {

    ApprovalRequest approve(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    );

    ApprovalRequest reject(
            UUID organizationId,
            UUID approvalRequestId,
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    );
}