package com.proofmesh.controlplane.approval;

import java.util.Objects;

public sealed interface ApprovalRequestInsertResult
        permits ApprovalRequestInsertResult.Inserted,
                ApprovalRequestInsertResult.Existing {

    record Inserted(
            ApprovalRequest approvalRequest
    ) implements ApprovalRequestInsertResult {

        public Inserted {
            Objects.requireNonNull(
                    approvalRequest,
                    "approvalRequest must not be null"
            );
        }
    }

    record Existing(
            ApprovalRequest approvalRequest
    ) implements ApprovalRequestInsertResult {

        public Existing {
            Objects.requireNonNull(
                    approvalRequest,
                    "approvalRequest must not be null"
            );
        }
    }
}