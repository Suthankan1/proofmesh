package com.proofmesh.controlplane.approval;

public class ApprovalRequestExpiredException
        extends RuntimeException {

    public ApprovalRequestExpiredException(
            String message
    ) {
        super(message);
    }
}