package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;

import java.util.Objects;

sealed interface RuntimePolicyVersionResolution
        permits RuntimePolicyVersionResolution.Available,
                RuntimePolicyVersionResolution.Failed {

    record Available(
            PolicyVersion policyVersion
    ) implements RuntimePolicyVersionResolution {

        public Available {
            Objects.requireNonNull(
                    policyVersion,
                    "policyVersion must not be null"
            );
        }
    }

    record Failed(
            RuntimeGovernanceFailureReason reason
    ) implements RuntimePolicyVersionResolution {

        public Failed {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }
}