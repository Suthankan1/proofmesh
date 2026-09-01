package com.proofmesh.controlplane.decision;

import java.util.Objects;

public sealed interface GovernanceDecisionInsertResult
        permits GovernanceDecisionInsertResult.Inserted,
                GovernanceDecisionInsertResult.Existing {

    record Inserted(
            GovernanceDecision decision
    ) implements GovernanceDecisionInsertResult {

        public Inserted {
            Objects.requireNonNull(
                    decision,
                    "decision must not be null"
            );
        }
    }

    record Existing(
            GovernanceDecision decision
    ) implements GovernanceDecisionInsertResult {

        public Existing {
            Objects.requireNonNull(
                    decision,
                    "decision must not be null"
            );
        }
    }
}