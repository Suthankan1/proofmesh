package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExecutionGrantSigningPropertiesTest {

    @Test
    void disabledAcceptsMissingOrNullKeyConfiguration() {
        ExecutionGrantSigningProperties properties = new ExecutionGrantSigningProperties(
                false,
                null,
                null,
                null
        );

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.keyId()).isNull();
        assertThat(properties.privateKeyPath()).isNull();
        assertThat(properties.publicKeyPath()).isNull();
    }

    @Test
    void disabledAcceptsBlankPathsWithoutValidationFailure() {
        ExecutionGrantSigningProperties properties = new ExecutionGrantSigningProperties(
                false,
                "",
                "   ",
                "\t\n"
        );

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.keyId()).isEmpty();
        assertThat(properties.privateKeyPath()).isEqualTo("   ");
        assertThat(properties.publicKeyPath()).isEqualTo("\t\n");
    }

    @Test
    void enabledAcceptsValidNonBlankValues() {
        ExecutionGrantSigningProperties properties = new ExecutionGrantSigningProperties(
                true,
                "pm-key-2026-1",
                "/secrets/signing-key.pem",
                "/secrets/signing-key-pub.pem"
        );

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.keyId()).isEqualTo("pm-key-2026-1");
        assertThat(properties.privateKeyPath()).isEqualTo("/secrets/signing-key.pem");
        assertThat(properties.publicKeyPath()).isEqualTo("/secrets/signing-key-pub.pem");
    }

    @Test
    void enabledRejectsNullKeyId() {
        assertThatThrownBy(() -> new ExecutionGrantSigningProperties(
                true,
                null,
                "/secrets/signing-key.pem",
                "/secrets/signing-key-pub.pem"
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("keyId must not be null");
    }

    @Test
    void enabledRejectsBlankOrWhitespaceKeyId() {
        assertThatThrownBy(() -> new ExecutionGrantSigningProperties(
                true,
                "   ",
                "/secrets/signing-key.pem",
                "/secrets/signing-key-pub.pem"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("keyId must not be blank");
    }

    @Test
    void enabledRejectsNullPrivateKeyPath() {
        assertThatThrownBy(() -> new ExecutionGrantSigningProperties(
                true,
                "pm-key-1",
                null,
                "/secrets/signing-key-pub.pem"
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("privateKeyPath must not be null");
    }

    @Test
    void enabledRejectsBlankPrivateKeyPath() {
        assertThatThrownBy(() -> new ExecutionGrantSigningProperties(
                true,
                "pm-key-1",
                "   ",
                "/secrets/signing-key-pub.pem"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("privateKeyPath must not be blank");
    }

    @Test
    void enabledRejectsNullPublicKeyPath() {
        assertThatThrownBy(() -> new ExecutionGrantSigningProperties(
                true,
                "pm-key-1",
                "/secrets/signing-key.pem",
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("publicKeyPath must not be null");
    }

    @Test
    void enabledRejectsBlankPublicKeyPath() {
        assertThatThrownBy(() -> new ExecutionGrantSigningProperties(
                true,
                "pm-key-1",
                "/secrets/signing-key.pem",
                "   "
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("publicKeyPath must not be blank");
    }

    @Test
    void validValuesArePreservedExactlyWithoutTrimmingKeyId() {
        String exactKeyId = "  key-with-surrounding-space  ";
        ExecutionGrantSigningProperties properties = new ExecutionGrantSigningProperties(
                true,
                exactKeyId,
                "/path/to/private.pem",
                "/path/to/public.pem"
        );

        assertThat(properties.keyId()).isEqualTo(exactKeyId);
        assertThat(properties.privateKeyPath()).isEqualTo("/path/to/private.pem");
        assertThat(properties.publicKeyPath()).isEqualTo("/path/to/public.pem");
    }
}
