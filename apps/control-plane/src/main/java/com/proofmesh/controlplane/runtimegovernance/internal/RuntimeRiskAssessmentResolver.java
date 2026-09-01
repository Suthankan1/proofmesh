package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

interface RuntimeRiskAssessmentResolver {

    RuntimeRiskAssessmentResolution resolve(
            RuntimeGovernanceRequest request
    );
}