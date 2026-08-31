package com.proofmesh.controlplane.governedaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GovernedActionValueObjectTest {

    @Test
    void acceptsValidRequestPayloadHash() {
        String hash =
                "a".repeat(64);

        RequestPayloadHash result =
                new RequestPayloadHash(hash);

        assertThat(result.value())
                .isEqualTo(hash);
    }

    @Test
    void rejectsInvalidRequestPayloadHash() {
        assertThatThrownBy(
                () -> new RequestPayloadHash(
                        "not-a-sha256-hash"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        assertThatThrownBy(
                () -> new IdempotencyKey(" ")
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsOverlongIdempotencyKey() {
        assertThatThrownBy(
                () -> new IdempotencyKey(
                        "a".repeat(129)
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsBlankToolName() {
        assertThatThrownBy(
                () -> new ToolName(" ")
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsBlankOperationName() {
        assertThatThrownBy(
                () -> new OperationName(" ")
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }
}