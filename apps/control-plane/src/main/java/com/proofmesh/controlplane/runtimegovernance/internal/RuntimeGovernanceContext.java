package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.PolicyVersion;

import java.util.Objects;

record RuntimeGovernanceContext(
        Agent agent,
        AgentPolicyBinding policyBinding,
        PolicyVersion policyVersion
) {

    RuntimeGovernanceContext {
        Objects.requireNonNull(
                agent,
                "agent must not be null"
        );

        Objects.requireNonNull(
                policyBinding,
                "policyBinding must not be null"
        );

        Objects.requireNonNull(
                policyVersion,
                "policyVersion must not be null"
        );

        if (!agent.organizationId()
                .equals(
                        policyBinding.organizationId()
                )) {
            throw new IllegalArgumentException(
                    "agent and policy binding organization must match"
            );
        }

        if (!agent.id()
                .equals(
                        policyBinding.agentId()
                )) {
            throw new IllegalArgumentException(
                    "agent and policy binding agent identity must match"
            );
        }

        if (!policyBinding.policyVersionId()
                .equals(
                        policyVersion.id()
                )) {
            throw new IllegalArgumentException(
                    "policy binding and policy version identity must match"
            );
        }

        if (!agent.organizationId()
                .equals(
                        policyVersion.organizationId()
                )) {
            throw new IllegalArgumentException(
                    "agent and policy version organization must match"
            );
        }
    }
}