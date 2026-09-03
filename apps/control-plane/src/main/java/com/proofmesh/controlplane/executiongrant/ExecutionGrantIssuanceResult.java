package com.proofmesh.controlplane.executiongrant;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface ExecutionGrantIssuanceResult
        permits ExecutionGrantIssuanceResult.Issued,
                ExecutionGrantIssuanceResult.Ineligible,
                ExecutionGrantIssuanceResult.StateUnavailable,
                ExecutionGrantIssuanceResult.StaleAuthorization {

    record Issued(
            SignedExecutionGrant grant
    ) implements ExecutionGrantIssuanceResult {

        public Issued {
            Objects.requireNonNull(
                    grant,
                    "grant must not be null"
            );
        }
    }

    record Ineligible(
            ExecutionGrantEligibility.Reason reason
    ) implements ExecutionGrantIssuanceResult {

        public Ineligible {
            Objects.requireNonNull(
                    reason,
                    "reason must not be null"
            );
        }
    }

    record StateUnavailable(
            UUID organizationId,
            UUID governedActionId
    ) implements ExecutionGrantIssuanceResult {

        public StateUnavailable {
            Objects.requireNonNull(
                    organizationId,
                    "organizationId must not be null"
            );
            Objects.requireNonNull(
                    governedActionId,
                    "governedActionId must not be null"
            );
        }
    }

    record StaleAuthorization(
            Instant attemptedAt,
            Instant effectiveExpiration
    ) implements ExecutionGrantIssuanceResult {

        public StaleAuthorization {
            Objects.requireNonNull(
                    attemptedAt,
                    "attemptedAt must not be null"
            );
            Objects.requireNonNull(
                    effectiveExpiration,
                    "effectiveExpiration must not be null"
            );
        }
    }
}
