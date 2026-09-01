package com.proofmesh.controlplane.policy;

public class PolicyDefinitionCanonicalizationException
        extends RuntimeException {

    public PolicyDefinitionCanonicalizationException(
            String message
    ) {
        super(message);
    }

    public PolicyDefinitionCanonicalizationException(
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );
    }
}