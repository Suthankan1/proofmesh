package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

interface RuntimeGovernanceContextResolver {

    RuntimeGovernanceContextResolution resolve(
            RuntimeGovernanceRequest request
    );
}