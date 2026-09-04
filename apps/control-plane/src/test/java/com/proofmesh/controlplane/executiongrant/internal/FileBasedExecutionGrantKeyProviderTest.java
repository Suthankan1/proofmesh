package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

class FileBasedExecutionGrantKeyProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void validP256Pkcs8PrivateAndMatchingX509PublicLoadsSuccessfully() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path privPath = writePem("valid-priv.pem", toPem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path pubPath = writePem("valid-pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));

        FileBasedExecutionGrantKeyProvider provider =
                new FileBasedExecutionGrantKeyProvider("key-p256-prod-1", privPath, pubPath);

        // 1. provider active signing key has exact key ID
        assertThat(provider.activeSigningKey().keyId()).isEqualTo("key-p256-prod-1");
        assertThat(provider.activeKeyId()).isEqualTo("key-p256-prod-1");

        // 2. returned private key is the loaded P-256 private key semantically
        assertThat(provider.activeSigningKey().privateKey()).isInstanceOf(ECPrivateKey.class);
        assertThat(provider.activeSigningKey().privateKey().getS())
                .isEqualTo(((ECPrivateKey) kp.getPrivate()).getS());

        // 3. internal public key accessor returns matching ECPublicKey
        assertThat(provider.activePublicKey()).isInstanceOf(ECPublicKey.class);
        assertThat(provider.activePublicKey().getW())
                .isEqualTo(((ECPublicKey) kp.getPublic()).getW());
    }

    @Test
    void missingPrivateFileFailsFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path pubPath = writePem("pub-only.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));
        Path nonExistentPriv = tempDir.resolve("does-not-exist-priv.pem");

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", nonExistentPriv, pubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read private key file at");
    }

    @Test
    void missingPublicFileFailsFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path privPath = writePem("priv-only.pem", toPem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path nonExistentPub = tempDir.resolve("does-not-exist-pub.pem");

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", privPath, nonExistentPub))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read public key file at");
    }

    @Test
    void malformedPrivatePemFailsFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path pubPath = writePem("pub-for-malformed.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));
        Path malformedPriv = writePem("malformed-priv.pem", "-----BEGIN PRIVATE KEY-----\nINVALID_BASE64_!@#$%\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", malformedPriv, pubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed Base64 in private key file");
    }

    @Test
    void malformedPublicPemFailsFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path privPath = writePem("priv-for-malformed.pem", toPem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path malformedPub = writePem("malformed-pub.pem", "-----BEGIN PUBLIC KEY-----\nINVALID_BASE64_!@#$%\n-----END PUBLIC KEY-----\n");

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", privPath, malformedPub))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed Base64 in public key file");
    }

    @Test
    void wrongPrivatePemLabelFailsFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path privPath = writePem("sec1-priv.pem", toPem("EC PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path pubPath = writePem("pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", privPath, pubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain expected PEM label 'PRIVATE KEY'");
    }

    @Test
    void wrongPublicPemLabelFailsFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path privPath = writePem("priv.pem", toPem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path pubPath = writePem("cert-pub.pem", toPem("CERTIFICATE", kp.getPublic().getEncoded()));

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", privPath, pubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain expected PEM label 'PUBLIC KEY'");
    }

    @Test
    void multiplePemObjectsInPrivateFileRejected() throws Exception {
        KeyPair kp = generateP256KeyPair();
        String doublePem = toPem("PRIVATE KEY", kp.getPrivate().getEncoded())
                + toPem("PRIVATE KEY", kp.getPrivate().getEncoded());
        Path privPath = writePem("double-priv.pem", doublePem);
        Path pubPath = writePem("pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", privPath, pubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private key file contains multiple PEM objects");
    }

    @Test
    void multiplePemObjectsInPublicFileRejected() throws Exception {
        KeyPair kp = generateP256KeyPair();
        String doublePem = toPem("PUBLIC KEY", kp.getPublic().getEncoded())
                + toPem("PUBLIC KEY", kp.getPublic().getEncoded());
        Path privPath = writePem("priv.pem", toPem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path pubPath = writePem("double-pub.pem", doublePem);

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", privPath, pubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public key file contains multiple PEM objects");
    }

    @Test
    void rsaPrivateMaterialRejected() throws Exception {
        KeyPair rsaKp = generateRsaKeyPair();
        KeyPair ecKp = generateP256KeyPair();
        Path rsaPrivPath = writePem("rsa-priv.pem", toPem("PRIVATE KEY", rsaKp.getPrivate().getEncoded()));
        Path ecPubPath = writePem("ec-pub.pem", toPem("PUBLIC KEY", ecKp.getPublic().getEncoded()));

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", rsaPrivPath, ecPubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse EC private key from");
    }

    @Test
    void rsaPublicMaterialRejected() throws Exception {
        KeyPair rsaKp = generateRsaKeyPair();
        KeyPair ecKp = generateP256KeyPair();
        Path ecPrivPath = writePem("ec-priv.pem", toPem("PRIVATE KEY", ecKp.getPrivate().getEncoded()));
        Path rsaPubPath = writePem("rsa-pub.pem", toPem("PUBLIC KEY", rsaKp.getPublic().getEncoded()));

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", ecPrivPath, rsaPubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse EC public key from");
    }

    @Test
    void p384KeyPairRejected() throws Exception {
        KeyPair p384Kp = generateP384KeyPair();
        Path privPath = writePem("p384-priv.pem", toPem("PRIVATE KEY", p384Kp.getPrivate().getEncoded()));
        Path pubPath = writePem("p384-pub.pem", toPem("PUBLIC KEY", p384Kp.getPublic().getEncoded()));

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", privPath, pubPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not use curve P-256 (secp256r1)");
    }

    @Test
    void mismatchedP256KeyPairRejected() throws Exception {
        KeyPair kp1 = generateP256KeyPair();
        KeyPair kp2 = generateP256KeyPair();
        Path privPath1 = writePem("priv1.pem", toPem("PRIVATE KEY", kp1.getPrivate().getEncoded()));
        Path pubPath2 = writePem("pub2.pem", toPem("PUBLIC KEY", kp2.getPublic().getEncoded()));

        assertThatThrownBy(() -> new FileBasedExecutionGrantKeyProvider("key-1", privPath1, pubPath2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not form a matching key pair");
    }

    @Test
    void toStringAndExceptionsNeverExposePrivateKeyMaterialOrScalar() throws Exception {
        KeyPair kp = generateP256KeyPair();
        ECPrivateKey privateKey = (ECPrivateKey) kp.getPrivate();
        String scalarString = privateKey.getS().toString();
        String base64Private = Base64.getEncoder().encodeToString(privateKey.getEncoded());

        Path privPath = writePem("secret-priv.pem", toPem("PRIVATE KEY", privateKey.getEncoded()));
        Path pubPath = writePem("secret-pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));

        FileBasedExecutionGrantKeyProvider provider =
                new FileBasedExecutionGrantKeyProvider("audit-key", privPath, pubPath);

        String repr = provider.toString();
        assertThat(repr).contains("keyId=audit-key");
        assertThat(repr).contains("[REDACTED]");
        assertThat(repr).doesNotContain(scalarString);
        assertThat(repr).doesNotContain(base64Private);

        // Also verify signing key toString
        String signingKeyRepr = provider.activeSigningKey().toString();
        assertThat(signingKeyRepr).contains("[REDACTED]");
        assertThat(signingKeyRepr).doesNotContain(scalarString);
        assertThat(signingKeyRepr).doesNotContain(base64Private);
    }

    private Path writePem(String fileName, String content) throws IOException {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, content);
        return path;
    }

    private static String toPem(String label, byte[] encoded) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    private static KeyPair generateP256KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    private static KeyPair generateP384KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp384r1"));
        return kpg.generateKeyPair();
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }
}
