package com.proofmesh.controlplane.executiongrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SignedExecutionGrantTest {

    private static final String SAMPLE_TOKEN =
            "eyJhbGciOiJFUzI1NiIsInR5cCI6InByb29mbWVzaC1leGVjdXRpb24tZ3JhbnQrand0In0.payload.sig";

    @Test
    void acceptsNonBlankTokenAndReturnsExactValue() {
        SignedExecutionGrant grant =
                new SignedExecutionGrant(SAMPLE_TOKEN);

        assertThat(grant.compactToken())
                .isEqualTo(SAMPLE_TOKEN);
    }

    @Test
    void rejectsNullToken() {
        assertThatThrownBy(
                () -> new SignedExecutionGrant(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("compactToken must not be null");
    }

    @Test
    void rejectsBlankToken() {
        assertThatThrownBy(
                () -> new SignedExecutionGrant("")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("compactToken must not be blank");
    }

    @Test
    void rejectsWhitespaceOnlyToken() {
        assertThatThrownBy(
                () -> new SignedExecutionGrant("   ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("compactToken must not be blank");
    }

    @Test
    void toStringContainsRedactionMarkerAndDoesNotContainToken() {
        SignedExecutionGrant grant =
                new SignedExecutionGrant(SAMPLE_TOKEN);

        String stringRepresentation = grant.toString();

        assertThat(stringRepresentation)
                .contains("REDACTED");
        assertThat(stringRepresentation)
                .doesNotContain(SAMPLE_TOKEN);
    }
}
