package com.proofmesh.controlplane.approval;

import java.time.Instant;
import java.util.Objects;

public sealed interface ApprovalState
        permits ApprovalState.Pending,
                ApprovalState.Approved,
                ApprovalState.Rejected,
                ApprovalState.Expired {

    ApprovalStatus status();

    record Pending()
            implements ApprovalState {

        @Override
        public ApprovalStatus status() {
            return ApprovalStatus.PENDING;
        }
    }

    record Approved(
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    ) implements ApprovalState {

        public Approved {
            Objects.requireNonNull(
                    actorId,
                    "actorId must not be null"
            );

            Objects.requireNonNull(
                    rationale,
                    "rationale must not be null"
            );

            Objects.requireNonNull(
                    decidedAt,
                    "decidedAt must not be null"
            );
        }

        @Override
        public ApprovalStatus status() {
            return ApprovalStatus.APPROVED;
        }
    }

    record Rejected(
            ApprovalActorId actorId,
            ApprovalRationale rationale,
            Instant decidedAt
    ) implements ApprovalState {

        public Rejected {
            Objects.requireNonNull(
                    actorId,
                    "actorId must not be null"
            );

            Objects.requireNonNull(
                    rationale,
                    "rationale must not be null"
            );

            Objects.requireNonNull(
                    decidedAt,
                    "decidedAt must not be null"
            );
        }

        @Override
        public ApprovalStatus status() {
            return ApprovalStatus.REJECTED;
        }
    }

    record Expired(
            Instant expiredAt
    ) implements ApprovalState {

        public Expired {
            Objects.requireNonNull(
                    expiredAt,
                    "expiredAt must not be null"
            );
        }

        @Override
        public ApprovalStatus status() {
            return ApprovalStatus.EXPIRED;
        }
    }
}