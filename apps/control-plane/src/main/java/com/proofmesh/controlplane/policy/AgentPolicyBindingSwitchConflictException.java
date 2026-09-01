package com.proofmesh.controlplane.policy;

public class AgentPolicyBindingSwitchConflictException
        extends RuntimeException {

    public AgentPolicyBindingSwitchConflictException(
            String message
    ) {
        super(message);
    }
}