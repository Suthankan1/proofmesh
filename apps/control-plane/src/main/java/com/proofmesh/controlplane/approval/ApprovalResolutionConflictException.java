package com.proofmesh.controlplane.approval;

public class ApprovalResolutionConflictException
        extends RuntimeException {

    public ApprovalResolutionConflictException(
            String message
    ) {
        super(message);
    }
}