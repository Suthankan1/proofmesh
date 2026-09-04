package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ExecutionGrantPolicyPropertiesTest {

    private static final String ISSUER = "proofmesh-control-plane";
    private static final String AUDIENCE = "proofmesh-gateway";
    private static final Duration GRANT_TTL = Duration.ofSeconds(30);

    @Test
    void validValuesPreservedExactly() {
        ExecutionGrantPolicyProperties properties =
                new ExecutionGrantPolicyProperties(ISSUER, AUDIENCE, GRANT_TTL);

        assertThat(properties.issuer()).isEqualTo(ISSUER);
        assertThat(properties.audience()).isEqualTo(AUDIENCE);
        assertThat(properties.grantTtl()).isEqualTo(GRANT_TTL);
    }

    @Test
    void rejectsNullIssuer() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties(null, AUDIENCE, GRANT_TTL))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("issuer must not be null");
    }

    @Test
    void rejectsBlankIssuer() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties("", AUDIENCE, GRANT_TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("issuer must not be blank");
    }

    @Test
    void rejectsWhitespaceOnlyIssuer() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties("   \t \n  ", AUDIENCE, GRANT_TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("issuer must not be blank");
    }

    @Test
    void rejectsNullAudience() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties(ISSUER, null, GRANT_TTL))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("audience must not be null");
    }

    @Test
    void rejectsBlankAudience() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties(ISSUER, "", GRANT_TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("audience must not be blank");
    }

    @Test
    void rejectsWhitespaceOnlyAudience() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties(ISSUER, "  \t  ", GRANT_TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("audience must not be blank");
    }

    @Test
    void rejectsNullGrantTtl() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties(ISSUER, AUDIENCE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("grantTtl must not be null");
    }

    @Test
    void rejectsZeroGrantTtl() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties(ISSUER, AUDIENCE, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("grantTtl must be positive");
    }

    @Test
    void rejectsNegativeGrantTtl() {
        assertThatThrownBy(() -> new ExecutionGrantPolicyProperties(ISSUER, AUDIENCE, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("grantTtl must be positive");
    }

    @Test
    void acceptsPositiveGrantTtl() {
        Duration customTtl = Duration.ofMillis(500);
        ExecutionGrantPolicyProperties properties =
                new ExecutionGrantPolicyProperties(ISSUER, AUDIENCE, customTtl);

        assertThat(properties.grantTtl()).isEqualTo(customTtl);
    }
}
