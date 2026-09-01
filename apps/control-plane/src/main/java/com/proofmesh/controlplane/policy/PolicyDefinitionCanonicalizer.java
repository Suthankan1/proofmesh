package com.proofmesh.controlplane.policy;

public interface PolicyDefinitionCanonicalizer {

    CanonicalPolicyDefinition canonicalize(
            PolicyDefinition definition
    );
}