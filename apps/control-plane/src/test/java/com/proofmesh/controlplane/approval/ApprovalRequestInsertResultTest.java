package com.proofmesh.controlplane.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class ApprovalRequestInsertResultTest {

    @Test
    void insertedCarriesApprovalRequest() {
        ApprovalRequest approvalRequest =
                approvalRequest();

        ApprovalRequestInsertResult.Inserted result =
                new ApprovalRequestInsertResult.Inserted(
                        approvalRequest
                );

        assertThat(
                result.approvalRequest()
        ).isEqualTo(
                approvalRequest
        );
    }

    @Test
    void existingCarriesAuthoritativeApprovalRequest() {
        ApprovalRequest approvalRequest =
                approvalRequest();

        ApprovalRequestInsertResult.Existing result =
                new ApprovalRequestInsertResult.Existing(
                        approvalRequest
                );

        assertThat(
                result.approvalRequest()
        ).isEqualTo(
                approvalRequest
        );
    }

    @Test
    void insertedRejectsNullApprovalRequest() {
        assertThatNullPointerException()
                .isThrownBy(
                        () -> new ApprovalRequestInsertResult.Inserted(
                                null
                        )
                )
                .withMessage(
                        "approvalRequest must not be null"
                );
    }

    @Test
    void existingRejectsNullApprovalRequest() {
        assertThatNullPointerException()
                .isThrownBy(
                        () -> new ApprovalRequestInsertResult.Existing(
                                null
                        )
                )
                .withMessage(
                        "approvalRequest must not be null"
                );
    }

    private ApprovalRequest approvalRequest() {
        return new ApprovalRequest(
                UUID.fromString(
                        "e1000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "e2000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "e3000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "e4000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "e5000000-0000-0000-0000-000000000001"
                ),
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                new RequestPayloadHash(
                        "a".repeat(64)
                ),
                Instant.parse(
                        "2026-09-01T12:00:00Z"
                ),
                Instant.parse(
                        "2026-09-01T12:15:00Z"
                ),
                new ApprovalState.Pending()
        );
    }
}