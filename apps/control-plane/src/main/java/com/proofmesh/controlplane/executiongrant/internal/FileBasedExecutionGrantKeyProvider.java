package com.proofmesh.controlplane.executiongrant.internal;

import com.nimbusds.jose.jwk.Curve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

final class FileBasedExecutionGrantKeyProvider implements ExecutionGrantSigningKeyProvider {

    private static final String TEST_PAYLOAD = "proofmesh-execution-grant-key-self-test";

    private final ExecutionGrantSigningKey signingKey;
    private final ECPublicKey publicKey;

    FileBasedExecutionGrantKeyProvider(
            String keyId,
            Path privateKeyPath,
            Path publicKeyPath
    ) {
        Objects.requireNonNull(
                keyId,
                "keyId must not be null"
        );
        if (keyId.isBlank()) {
            throw new IllegalArgumentException(
                    "keyId must not be blank"
                );
        }
        Objects.requireNonNull(
                privateKeyPath,
                "privateKeyPath must not be null"
        );
        Objects.requireNonNull(
                publicKeyPath,
                "publicKeyPath must not be null"
        );

        ECPrivateKey privateKey = parsePrivateKey(privateKeyPath);
        ECPublicKey pubKey = parsePublicKey(publicKeyPath);

        validateP256(privateKey.getParams(), "private", privateKeyPath);
        validateP256(pubKey.getParams(), "public", publicKeyPath);

        verifyKeyPair(privateKey, pubKey, privateKeyPath, publicKeyPath);

        this.signingKey = new ExecutionGrantSigningKey(keyId, privateKey);
        this.publicKey = pubKey;
    }

    FileBasedExecutionGrantKeyProvider(ExecutionGrantSigningProperties properties) {
        this(
                Objects.requireNonNull(properties, "properties must not be null").keyId(),
                Path.of(Objects.requireNonNull(properties.privateKeyPath(), "privateKeyPath must not be null")),
                Path.of(Objects.requireNonNull(properties.publicKeyPath(), "publicKeyPath must not be null"))
        );
    }

    @Override
    public ExecutionGrantSigningKey activeSigningKey() {
        return signingKey;
    }

    String activeKeyId() {
        return signingKey.keyId();
    }

    ECPublicKey activePublicKey() {
        return publicKey;
    }

    @Override
    public String toString() {
        return "FileBasedExecutionGrantKeyProvider[keyId=" + activeKeyId()
                + ", publicKey=[PRESENT], privateKey=[REDACTED]]";
    }

    private static ECPrivateKey parsePrivateKey(Path path) {
        byte[] der = parseSinglePemObject(path, "PRIVATE KEY", "private");
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (privateKey instanceof ECPrivateKey ecPrivateKey) {
                return ecPrivateKey;
            }
            throw new IllegalStateException("Configured private key at " + path + " is not an EC private key");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to parse EC private key from " + path, e);
        }
    }

    private static ECPublicKey parsePublicKey(Path path) {
        byte[] der = parseSinglePemObject(path, "PUBLIC KEY", "public");
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(der));
            if (pubKey instanceof ECPublicKey ecPublicKey) {
                return ecPublicKey;
            }
            throw new IllegalStateException("Configured public key at " + path + " is not an EC public key");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to parse EC public key from " + path, e);
        }
    }

    private static void validateP256(ECParameterSpec params, String role, Path path) {
        if (params == null) {
            throw new IllegalStateException(role + " key at " + path + " has null EC parameters");
        }
        Curve curve = Curve.forECParameterSpec(params);
        if (!Curve.P_256.equals(curve)) {
            throw new IllegalStateException(
                    role + " key at " + path + " does not use curve P-256 (secp256r1)"
            );
        }
    }

    private static void verifyKeyPair(
            ECPrivateKey privateKey,
            ECPublicKey publicKey,
            Path privatePath,
            Path publicPath
    ) {
        byte[] payload = TEST_PAYLOAD.getBytes(StandardCharsets.US_ASCII);
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(payload);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            boolean verified = verifier.verify(signature);

            if (!verified) {
                throw new IllegalStateException(
                        "Configured private key (" + privatePath
                                + ") and public key (" + publicPath
                                + ") do not form a matching key pair"
                );
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Cryptographic self-test failed for configured key pair at "
                            + privatePath + " and " + publicPath,
                    e
            );
        }
    }

    private static byte[] parseSinglePemObject(Path path, String expectedLabel, String role) {
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + role + " key file at " + path, e);
        }

        if (content.isBlank()) {
            throw new IllegalStateException(role + " key file is empty: " + path);
        }

        String beginMarker = "-----BEGIN " + expectedLabel + "-----";
        String endMarker = "-----END " + expectedLabel + "-----";

        int beginCount = countSubstring(content, "-----BEGIN ");
        int endCount = countSubstring(content, "-----END ");

        if (beginCount == 0 || endCount == 0) {
            throw new IllegalStateException(role + " key file does not contain PEM markers: " + path);
        }
        if (beginCount > 1 || endCount > 1) {
            throw new IllegalStateException(role + " key file contains multiple PEM objects: " + path);
        }

        int beginIndex = content.indexOf(beginMarker);
        int endIndex = content.indexOf(endMarker);

        if (beginIndex == -1 || endIndex == -1 || endIndex <= beginIndex) {
            throw new IllegalStateException(
                    role + " key file does not contain expected PEM label '" + expectedLabel + "': " + path
            );
        }

        String prefix = content.substring(0, beginIndex).trim();
        String suffix = content.substring(endIndex + endMarker.length()).trim();
        if (!prefix.isEmpty() || !suffix.isEmpty()) {
            throw new IllegalStateException(
                    role + " key file contains invalid content outside PEM block: " + path
            );
        }

        String base64 = content.substring(beginIndex + beginMarker.length(), endIndex).replaceAll("\\s+", "");
        if (base64.isEmpty()) {
            throw new IllegalStateException(role + " key file contains empty PEM payload: " + path);
        }

        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed Base64 in " + role + " key file: " + path, e);
        }
    }

    private static int countSubstring(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
