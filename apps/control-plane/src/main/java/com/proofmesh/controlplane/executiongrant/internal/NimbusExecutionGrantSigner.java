package com.proofmesh.controlplane.executiongrant.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaims;
import com.proofmesh.controlplane.executiongrant.SignedExecutionGrant;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;

final class NimbusExecutionGrantSigner implements ExecutionGrantSigner {

    private static final JOSEObjectType GRANT_TYPE =
            new JOSEObjectType("proofmesh-execution-grant+jwt");

    @Override
    public SignedExecutionGrant sign(
            ExecutionGrantClaims claims,
            ExecutionGrantSigningKey signingKey
    ) {
        Objects.requireNonNull(
                claims,
                "claims must not be null"
        );

        Objects.requireNonNull(
                signingKey,
                "signingKey must not be null"
        );

        long iatSeconds = claims.issuedAt().getEpochSecond();
        long expSeconds = claims.expiresAt().getEpochSecond();

        if (expSeconds <= iatSeconds) {
            throw new ExecutionGrantSigningException(
                    "Execution grant validity window collapsed at second precision: issuedAt="
                            + claims.issuedAt()
                            + " (" + iatSeconds + "s), expiresAt="
                            + claims.expiresAt()
                            + " (" + expSeconds + "s)"
            );
        }

        Date issueTime = Date.from(Instant.ofEpochSecond(iatSeconds));
        Date expirationTime = Date.from(Instant.ofEpochSecond(expSeconds));

        JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                .issuer(claims.issuer())
                .audience(claims.audience())
                .jwtID(claims.grantId().value().toString())
                .issueTime(issueTime)
                .expirationTime(expirationTime)
                .claim("org_id", claims.organizationId().toString())
                .claim("agent_id", claims.agentId().toString())
                .claim("action_id", claims.governedActionId().toString())
                .claim("decision_id", claims.governanceDecisionId().toString())
                .claim("tool_name", claims.toolName().value())
                .claim("operation_name", claims.operationName().value())
                .claim("payload_hash", claims.requestPayloadHash().value())
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(GRANT_TYPE)
                .keyID(signingKey.keyId())
                .build();

        SignedJWT signedJWT = new SignedJWT(header, jwtClaimsSet);

        try {
            JWSSigner signer = new ECDSASigner(signingKey.privateKey());
            signedJWT.sign(signer);
            String compactToken = signedJWT.serialize();
            return new SignedExecutionGrant(compactToken);
        } catch (JOSEException | RuntimeException e) {
            throw new ExecutionGrantSigningException(
                    "Failed to sign execution grant: " + e.getMessage(),
                    e
            );
        }
    }
}
