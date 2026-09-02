package com.proofmesh.controlplane.executiongrant;

import java.util.Objects;

public sealed interface ExecutionGrantEligibility
        permits ExecutionGrantEligibility.Eligible,
                ExecutionGrantEligibility.Ineligible {

    record Eligible()
            implements ExecutionGrantEligibility {
    }

    record Ineligible(
            Reason reason
    ) implements ExecutionGrantEligibility {

        public Ineligible {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }

    enum Reason {
        FAILED_CLOSED,
        ACTION_PROVENANCE_MISMATCH,
        DECISION_DENIED,
        APPROVAL_NOT_CURRENTLY_VALID,
        APPROVAL_PROVENANCE_MISMATCH
    }
}
