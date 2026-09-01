package com.proofmesh.controlplane.policy;

public class PolicyVersionWriteConflictException
        extends RuntimeException {

    public PolicyVersionWriteConflictException(
            String message
    ) {
        super(message);
    }
}