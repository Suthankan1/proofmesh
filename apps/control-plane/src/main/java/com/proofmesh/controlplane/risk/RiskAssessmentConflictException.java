package com.proofmesh.controlplane.risk;

public class RiskAssessmentConflictException
        extends RuntimeException {

    public RiskAssessmentConflictException(
            String message
    ) {
        super(message);
    }
}