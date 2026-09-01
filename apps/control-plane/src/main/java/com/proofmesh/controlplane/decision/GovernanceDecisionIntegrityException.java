package com.proofmesh.controlplane.decision;

public class GovernanceDecisionIntegrityException
        extends RuntimeException {

    public GovernanceDecisionIntegrityException(
            String message
    ) {
        super(message);
    }

    public GovernanceDecisionIntegrityException(
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );
    }
}