package com.proofmesh.controlplane.runtimegovernance;

public interface RuntimeGovernanceOrchestrator {

    RuntimeGovernanceResult govern(
            RuntimeGovernanceRequest request
    );
}