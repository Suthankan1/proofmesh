package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;

import java.util.Objects;

sealed interface RuntimeGovernanceContextResolution
        permits RuntimeGovernanceContextResolution.Ready,
                RuntimeGovernanceContextResolution.Failed {

    record Ready(
            RuntimeGovernanceContext context
    ) implements RuntimeGovernanceContextResolution {

        public Ready {
            Objects.requireNonNull(
                    context,
                    "context must not be null"
            );
        }
    }

    record Failed(
            RuntimeGovernanceFailureReason reason
    ) implements RuntimeGovernanceContextResolution {

        public Failed {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }
}