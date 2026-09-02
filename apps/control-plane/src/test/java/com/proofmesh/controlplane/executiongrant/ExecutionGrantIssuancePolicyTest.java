package com.proofmesh.controlplane.executiongrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ExecutionGrantIssuancePolicyTest {

    private static final String ISSUER =
            "proofmesh-control-plane";

    private static final String AUDIENCE =
            "proofmesh-gateway";

    private static final Duration GRANT_TTL =
            Duration.ofSeconds(
                    30
            );

    @Test
    void validPolicyPreservesAllValues() {
        ExecutionGrantIssuancePolicy policy =
                new ExecutionGrantIssuancePolicy(
                        ISSUER,
                        AUDIENCE,
                        GRANT_TTL
                );

        assertThat(policy.issuer())
                .isEqualTo(ISSUER);
        assertThat(policy.audience())
                .isEqualTo(AUDIENCE);
        assertThat(policy.grantTtl())
                .isEqualTo(GRANT_TTL);
    }

    @Test
    void rejectsNullIssuer() {
        assertThatThrownBy(
                () -> new ExecutionGrantIssuancePolicy(
                        null,
                        AUDIENCE,
                        GRANT_TTL
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "issuer must not be null"
                );
    }

    @Test
    void rejectsBlankIssuer() {
        assertThatThrownBy(
                () -> new ExecutionGrantIssuancePolicy(
                        "   ",
                        AUDIENCE,
                        GRANT_TTL
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "issuer must not be blank"
                );
    }

    @Test
    void rejectsNullAudience() {
        assertThatThrownBy(
                () -> new ExecutionGrantIssuancePolicy(
                        ISSUER,
                        null,
                        GRANT_TTL
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "audience must not be null"
                );
    }

    @Test
    void rejectsBlankAudience() {
        assertThatThrownBy(
                () -> new ExecutionGrantIssuancePolicy(
                        ISSUER,
                        "   ",
                        GRANT_TTL
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "audience must not be blank"
                );
    }

    @Test
    void rejectsNullGrantTtl() {
        assertThatThrownBy(
                () -> new ExecutionGrantIssuancePolicy(
                        ISSUER,
                        AUDIENCE,
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "grantTtl must not be null"
                );
    }

    @Test
    void rejectsZeroGrantTtl() {
        assertThatThrownBy(
                () -> new ExecutionGrantIssuancePolicy(
                        ISSUER,
                        AUDIENCE,
                        Duration.ZERO
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "grantTtl must be positive"
                );
    }

    @Test
    void rejectsNegativeGrantTtl() {
        assertThatThrownBy(
                () -> new ExecutionGrantIssuancePolicy(
                        ISSUER,
                        AUDIENCE,
                        Duration.ofSeconds(-1)
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "grantTtl must be positive"
                );
    }
}
