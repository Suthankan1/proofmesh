package com.proofmesh.controlplane.governedaction;

public class GovernedActionIntegrityException
        extends RuntimeException {

    public GovernedActionIntegrityException(
            String message
    ) {
        super(message);
    }

    public GovernedActionIntegrityException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}