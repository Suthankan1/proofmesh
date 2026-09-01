package com.proofmesh.controlplane.policy;

public class PolicyVersionIntegrityException
        extends RuntimeException {

    public PolicyVersionIntegrityException(
            String message
    ) {
        super(message);
    }

    public PolicyVersionIntegrityException(
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );
    }
}