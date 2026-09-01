package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;

import java.time.Instant;

interface RuntimePolicyVersionResolver {

    RuntimePolicyVersionResolution resolve(
            AgentPolicyBinding binding,
            Instant evaluatedAt
    );
}