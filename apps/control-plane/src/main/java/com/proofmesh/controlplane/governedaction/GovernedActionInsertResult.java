package com.proofmesh.controlplane.governedaction;

import java.util.Objects;

public sealed interface GovernedActionInsertResult
        permits GovernedActionInsertResult.Inserted,
                GovernedActionInsertResult.Existing {

    record Inserted(
            GovernedAction governedAction
    ) implements GovernedActionInsertResult {

        public Inserted {
            Objects.requireNonNull(
                    governedAction,
                    "governedAction must not be null"
            );
        }
    }

    record Existing(
            GovernedAction governedAction
    ) implements GovernedActionInsertResult {

        public Existing {
            Objects.requireNonNull(
                    governedAction,
                    "governedAction must not be null"
            );
        }
    }
}