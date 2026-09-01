package com.proofmesh.controlplane.approval;

public class ApprovalRequestIntegrityException
        extends RuntimeException {

    public ApprovalRequestIntegrityException(
            String message
    ) {
        super(message);
    }

    public ApprovalRequestIntegrityException(
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );
    }
}