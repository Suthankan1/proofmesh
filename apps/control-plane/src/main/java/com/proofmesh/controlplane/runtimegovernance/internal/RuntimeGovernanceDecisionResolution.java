package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;

import java.util.Objects;

sealed interface RuntimeGovernanceDecisionResolution
        permits RuntimeGovernanceDecisionResolution.Ready,
                RuntimeGovernanceDecisionResolution.Failed {

    record Ready(
            GovernanceDecision decision
    ) implements RuntimeGovernanceDecisionResolution {

        public Ready {
            Objects.requireNonNull(
                    decision,
                    "decision must not be null"
            );
        }
    }

    record Failed(
            RuntimeGovernanceFailureReason reason
    ) implements RuntimeGovernanceDecisionResolution {

        public Failed {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }
}