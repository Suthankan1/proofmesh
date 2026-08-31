package com.proofmesh.controlplane.governedaction;

import java.util.Objects;

public sealed interface CreateGovernedActionResult
        permits CreateGovernedActionResult.Created,
                CreateGovernedActionResult.Existing,
                CreateGovernedActionResult.Rejected {

    record Created(
            GovernedAction governedAction
    ) implements CreateGovernedActionResult {

        public Created {
            Objects.requireNonNull(
                    governedAction,
                    "governedAction must not be null"
            );
        }
    }

    record Existing(
            GovernedAction governedAction
    ) implements CreateGovernedActionResult {

        public Existing {
            Objects.requireNonNull(
                    governedAction,
                    "governedAction must not be null"
            );
        }
    }

    record Rejected(
            RejectionReason reason
    ) implements CreateGovernedActionResult {

        public Rejected {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }

    enum RejectionReason {
        UNKNOWN_AGENT,
        AGENT_DISABLED
    }
}