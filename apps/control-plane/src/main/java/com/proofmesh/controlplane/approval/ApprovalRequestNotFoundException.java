package com.proofmesh.controlplane.approval;

public class ApprovalRequestNotFoundException
        extends RuntimeException {

    public ApprovalRequestNotFoundException(
            String message
    ) {
        super(message);
    }
}