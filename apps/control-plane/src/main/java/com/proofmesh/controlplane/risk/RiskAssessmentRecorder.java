package com.proofmesh.controlplane.risk;

public interface RiskAssessmentRecorder {

    RiskAssessment record(
            RiskAssessment proposedAssessment
    );
}