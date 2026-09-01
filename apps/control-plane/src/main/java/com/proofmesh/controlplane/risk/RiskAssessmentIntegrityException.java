package com.proofmesh.controlplane.risk;

public class RiskAssessmentIntegrityException
        extends RuntimeException {

    public RiskAssessmentIntegrityException(
            String message
    ) {
        super(message);
    }

    public RiskAssessmentIntegrityException(
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );
    }
}