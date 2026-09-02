package com.proofmesh.controlplane.executiongrant;

import java.util.Objects;

public sealed interface ExecutionGrantClaimsPreparationResult
        permits ExecutionGrantClaimsPreparationResult.Prepared,
                ExecutionGrantClaimsPreparationResult.Ineligible {

    record Prepared(
            ExecutionGrantClaims claims
    ) implements ExecutionGrantClaimsPreparationResult {

        public Prepared {
            Objects.requireNonNull(
                    claims,
                    "claims must not be null"
            );
        }
    }

    record Ineligible(
            ExecutionGrantEligibility.Reason reason
    ) implements ExecutionGrantClaimsPreparationResult {

        public Ineligible {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }
}
