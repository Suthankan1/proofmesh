package com.proofmesh.controlplane.policy;

public class InvalidPolicyDefinitionException
        extends IllegalArgumentException {

    public InvalidPolicyDefinitionException(
            String message
    ) {
        super(message);
    }

    public InvalidPolicyDefinitionException(
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );
    }
}