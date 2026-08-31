package com.proofmesh.controlplane.governedaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class GovernedActionTest {

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "70000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "80000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "90000000-0000-0000-0000-000000000001"
            );

    @Test
    void exposesRequestPayloadHash() {
        RequestPayloadHash hash =
                new RequestPayloadHash(
                        "a".repeat(64)
                );

        CanonicalRequestPayload payload =
                new CanonicalRequestPayload(
                        "{\"amount\":5000}",
                        hash
                );

        GovernedAction action =
                createAction(
                        payload
                );

        assertThat(
                action.requestPayloadHash()
        )
                .isEqualTo(hash);
    }

    @Test
    void identifiesOwningOrganization() {
        GovernedAction action =
                createAction(
                        new CanonicalRequestPayload(
                                "{}",
                                new RequestPayloadHash(
                                        "b".repeat(64)
                                )
                        )
                );

        assertThat(
                action.belongsToOrganization(
                        ORGANIZATION_ID
                )
        )
                .isTrue();

        assertThat(
                action.belongsToOrganization(
                        UUID.fromString(
                                "80000000-0000-0000-0000-000000000002"
                        )
                )
        )
                .isFalse();
    }

    private GovernedAction createAction(
            CanonicalRequestPayload payload
    ) {
        return new GovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey(
                        "request-001"
                ),
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                payload,
                Instant.parse(
                        "2026-08-30T04:30:00Z"
                )
        );
    }
}