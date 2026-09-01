package com.proofmesh.controlplane.policy;

public class AgentPolicyBindingIntegrityException
        extends RuntimeException {

    public AgentPolicyBindingIntegrityException(
            String message
    ) {
        super(message);
    }

    public AgentPolicyBindingIntegrityException(
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );
    }
}