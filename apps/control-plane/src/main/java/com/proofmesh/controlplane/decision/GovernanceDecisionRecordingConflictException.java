package com.proofmesh.controlplane.decision;

public class GovernanceDecisionRecordingConflictException
        extends RuntimeException {

    public GovernanceDecisionRecordingConflictException(
            String message
    ) {
        super(message);
    }
}