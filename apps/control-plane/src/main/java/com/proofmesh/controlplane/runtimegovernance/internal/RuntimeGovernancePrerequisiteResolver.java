package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

interface RuntimeGovernancePrerequisiteResolver {

    RuntimeGovernancePrerequisiteResolution resolve(
            RuntimeGovernanceRequest request
    );
}