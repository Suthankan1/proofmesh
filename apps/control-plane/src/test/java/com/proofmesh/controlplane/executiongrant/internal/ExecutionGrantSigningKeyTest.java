package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;

class ExecutionGrantSigningKeyTest {

    private static ECPrivateKey testPrivateKey;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        testPrivateKey = (ECPrivateKey) keyPair.getPrivate();
    }

    @Test
    void validKeyAcceptedAndPreservesExactValues() {
        String keyId = "key-2026-09-01";
        ExecutionGrantSigningKey signingKey =
                new ExecutionGrantSigningKey(keyId, testPrivateKey);

        assertThat(signingKey.keyId())
                .isEqualTo(keyId);
        assertThat(signingKey.privateKey())
                .isSameAs(testPrivateKey);
    }

    @Test
    void rejectsNullKeyId() {
        assertThatThrownBy(
                () -> new ExecutionGrantSigningKey(null, testPrivateKey)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("keyId must not be null");
    }

    @Test
    void rejectsBlankKeyId() {
        assertThatThrownBy(
                () -> new ExecutionGrantSigningKey("", testPrivateKey)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("keyId must not be blank");
    }

    @Test
    void rejectsWhitespaceOnlyKeyId() {
        assertThatThrownBy(
                () -> new ExecutionGrantSigningKey("   ", testPrivateKey)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("keyId must not be blank");
    }

    @Test
    void rejectsNullPrivateKey() {
        assertThatThrownBy(
                () -> new ExecutionGrantSigningKey("key-1", null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("privateKey must not be null");
    }

    @Test
    void toStringRedactsPrivateKeyMaterial() {
        String keyId = "key-opaque-id";
        ExecutionGrantSigningKey signingKey =
                new ExecutionGrantSigningKey(keyId, testPrivateKey);

        String stringRepresentation = signingKey.toString();

        assertThat(stringRepresentation)
                .contains(keyId);
        assertThat(stringRepresentation)
                .contains("REDACTED");
        assertThat(stringRepresentation)
                .doesNotContain(testPrivateKey.getS().toString());
        assertThat(stringRepresentation)
                .doesNotContain(testPrivateKey.toString());
    }
}
