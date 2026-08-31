package com.proofmesh.controlplane.governedaction.internal.canonicalization;

import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.InvalidRequestPayloadException;
import com.proofmesh.controlplane.governedaction.RequestPayloadCanonicalizer;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;

import org.erdtman.jcs.JsonCanonicalizer;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Component
class Rfc8785RequestPayloadCanonicalizer
        implements RequestPayloadCanonicalizer {

    private static final String HASH_ALGORITHM =
            "SHA-256";

    @Override
    public CanonicalRequestPayload canonicalize(
            String json
    ) {
        Objects.requireNonNull(
                json,
                "json must not be null"
        );

        if (json.isBlank()) {
            throw new InvalidRequestPayloadException(
                    "request payload must not be blank"
            );
        }

        final String canonicalJson;

        try {
            JsonCanonicalizer canonicalizer =
                    new JsonCanonicalizer(json);

            canonicalJson =
                    canonicalizer.getEncodedString();
        } catch (IOException exception) {
            throw new InvalidRequestPayloadException(
                    "request payload must be valid RFC 8785-compatible JSON",
                    exception
            );
        }

        if (!canonicalJson.startsWith("{")) {
            throw new InvalidRequestPayloadException(
                    "request payload must be a JSON object"
            );
        }

        byte[] canonicalBytes =
                canonicalJson.getBytes(
                        StandardCharsets.UTF_8
                );

        byte[] digest =
                sha256(canonicalBytes);

        String hexadecimalHash =
                HexFormat.of()
                        .formatHex(digest);

        return new CanonicalRequestPayload(
                canonicalJson,
                new RequestPayloadHash(
                        hexadecimalHash
                )
        );
    }

    private byte[] sha256(
            byte[] input
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            HASH_ALGORITHM
                    );

            return digest.digest(input);
        } catch (
                NoSuchAlgorithmException exception
        ) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable in the current Java runtime",
                    exception
            );
        }
    }
}