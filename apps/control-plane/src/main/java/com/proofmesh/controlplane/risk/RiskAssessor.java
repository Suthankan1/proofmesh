package com.proofmesh.controlplane.risk;

public interface RiskAssessor {

    RiskAssessment assess(
            RiskAssessmentRequest request
    );
}