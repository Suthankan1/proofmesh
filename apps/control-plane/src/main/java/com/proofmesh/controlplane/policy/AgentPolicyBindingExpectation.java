package com.proofmesh.controlplane.policy;

import java.util.Objects;
import java.util.UUID;

public sealed interface AgentPolicyBindingExpectation
        permits AgentPolicyBindingExpectation.None,
                AgentPolicyBindingExpectation.Existing {

    record None()
            implements AgentPolicyBindingExpectation {
    }

    record Existing(
            UUID bindingId
    ) implements AgentPolicyBindingExpectation {

        public Existing {
            Objects.requireNonNull(
                    bindingId,
                    "bindingId must not be null"
            );
        }
    }
}