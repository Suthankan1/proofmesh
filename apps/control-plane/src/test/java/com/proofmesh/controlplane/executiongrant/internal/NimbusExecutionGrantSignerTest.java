package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaims;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantId;
import com.proofmesh.controlplane.executiongrant.SignedExecutionGrant;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class NimbusExecutionGrantSignerTest {

    private static final String KEY_ID = "key-2026-test-01";
    private static KeyPair testKeyPair;
    private static ExecutionGrantSigningKey testSigningKey;
    private static NimbusExecutionGrantSigner signer;

    private static final ExecutionGrantId GRANT_ID =
            new ExecutionGrantId(
                    UUID.fromString(
                            "71000000-0000-0000-0000-000000000001"
                    )
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "72000000-0000-0000-0000-000000000001"
                    );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "73000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "74000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNANCE_DECISION_ID =
            UUID.fromString(
                    "75000000-0000-0000-0000-000000000001"
            );

    private static final ToolName TOOL_NAME =
            new ToolName("github");

    private static final OperationName OPERATION_NAME =
            new OperationName("create_issue");

    private static final RequestPayloadHash REQUEST_PAYLOAD_HASH =
            new RequestPayloadHash("a".repeat(64));

    private static final String ISSUER = "proofmesh-control-plane";
    private static final String AUDIENCE = "proofmesh-gateway";

    private static final Instant ISSUED_AT =
            Instant.parse("2026-09-02T10:00:00.123456789Z");

    private static final Instant EXPIRES_AT =
            Instant.parse("2026-09-02T10:00:30.987654321Z");

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        testKeyPair = keyPairGenerator.generateKeyPair();
        testSigningKey = new ExecutionGrantSigningKey(
                KEY_ID,
                (ECPrivateKey) testKeyPair.getPrivate()
        );
        signer = new NimbusExecutionGrantSigner();
    }

    @Test
    void signsValidClaimsIntoThreePartCompactJws() throws Exception {
        ExecutionGrantClaims claims = validClaims();

        SignedExecutionGrant grant = signer.sign(claims, testSigningKey);

        assertThat(grant).isNotNull();
        String compactToken = grant.compactToken();
        assertThat(compactToken).isNotBlank();

        String[] parts = compactToken.split("\\.");
        assertThat(parts).hasSize(3);

        SignedJWT parsedJwt = SignedJWT.parse(compactToken);
        assertThat(parsedJwt).isNotNull();
    }

    @Test
    void protectedHeaderContainsOnlyRequiredExactFields() throws Exception {
        ExecutionGrantClaims claims = validClaims();

        SignedExecutionGrant grant = signer.sign(claims, testSigningKey);
        SignedJWT parsedJwt = SignedJWT.parse(grant.compactToken());
        JWSHeader header = parsedJwt.getHeader();

        assertThat(header.getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(header.getType().toString()).isEqualTo("proofmesh-execution-grant+jwt");
        assertThat(header.getKeyID()).isEqualTo(KEY_ID);

        assertThat(header.toJSONObject().keySet())
                .containsExactlyInAnyOrder("alg", "typ", "kid");
    }

    @Test
    void matchingPublicKeyVerifiesSignatureAndDifferentKeyFails() throws Exception {
        ExecutionGrantClaims claims = validClaims();

        SignedExecutionGrant grant = signer.sign(claims, testSigningKey);
        SignedJWT parsedJwt = SignedJWT.parse(grant.compactToken());

        ECPublicKey matchingPublicKey = (ECPublicKey) testKeyPair.getPublic();
        boolean verifiedWithMatchingKey =
                parsedJwt.verify(new ECDSAVerifier(matchingPublicKey));
        assertThat(verifiedWithMatchingKey).isTrue();

        KeyPairGenerator otherKpg = KeyPairGenerator.getInstance("EC");
        otherKpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair otherKeyPair = otherKpg.generateKeyPair();
        ECPublicKey differentPublicKey = (ECPublicKey) otherKeyPair.getPublic();

        boolean verifiedWithDifferentKey =
                parsedJwt.verify(new ECDSAVerifier(differentPublicKey));
        assertThat(verifiedWithDifferentKey).isFalse();
    }

    @Test
    void standardClaimsAreMappedExactlyWithoutForbiddenClaims() throws Exception {
        ExecutionGrantClaims claims = validClaims();

        SignedExecutionGrant grant = signer.sign(claims, testSigningKey);
        SignedJWT parsedJwt = SignedJWT.parse(grant.compactToken());
        JWTClaimsSet claimsSet = parsedJwt.getJWTClaimsSet();

        assertThat(claimsSet.getIssuer()).isEqualTo(ISSUER);
        assertThat(claimsSet.getAudience()).isEqualTo(List.of(AUDIENCE));

        Object rawAud = claimsSet.toJSONObject().get("aud");
        assertThat(rawAud).isInstanceOf(String.class);
        assertThat(rawAud).isEqualTo(AUDIENCE);

        assertThat(claimsSet.getJWTID()).isEqualTo(GRANT_ID.value().toString());

        assertThat(claimsSet.getSubject()).isNull();
        assertThat(claimsSet.getNotBeforeTime()).isNull();
        assertThat(claimsSet.getClaims()).doesNotContainKey("sub");
        assertThat(claimsSet.getClaims()).doesNotContainKey("nbf");
        assertThat(claimsSet.getClaims()).doesNotContainKey("grant_id");
    }

    @Test
    void customClaimsAreMappedExactly() throws Exception {
        ExecutionGrantClaims claims = validClaims();

        SignedExecutionGrant grant = signer.sign(claims, testSigningKey);
        SignedJWT parsedJwt = SignedJWT.parse(grant.compactToken());
        JWTClaimsSet claimsSet = parsedJwt.getJWTClaimsSet();

        assertThat(claimsSet.getStringClaim("org_id"))
                .isEqualTo(ORGANIZATION_ID.toString());
        assertThat(claimsSet.getStringClaim("agent_id"))
                .isEqualTo(AGENT_ID.toString());
        assertThat(claimsSet.getStringClaim("action_id"))
                .isEqualTo(GOVERNED_ACTION_ID.toString());
        assertThat(claimsSet.getStringClaim("decision_id"))
                .isEqualTo(GOVERNANCE_DECISION_ID.toString());
        assertThat(claimsSet.getStringClaim("tool_name"))
                .isEqualTo(TOOL_NAME.value());
        assertThat(claimsSet.getStringClaim("operation_name"))
                .isEqualTo(OPERATION_NAME.value());
        assertThat(claimsSet.getStringClaim("payload_hash"))
                .isEqualTo(REQUEST_PAYLOAD_HASH.value());
    }

    @Test
    void timeClaimsAreNormalizedToWholeSecondsAndDoNotRoundUp() throws Exception {
        ExecutionGrantClaims claims = validClaims();

        SignedExecutionGrant grant = signer.sign(claims, testSigningKey);
        SignedJWT parsedJwt = SignedJWT.parse(grant.compactToken());
        JWTClaimsSet claimsSet = parsedJwt.getJWTClaimsSet();

        Instant parsedIat = claimsSet.getIssueTime().toInstant();
        Instant parsedExp = claimsSet.getExpirationTime().toInstant();

        Instant expectedIat = Instant.ofEpochSecond(ISSUED_AT.getEpochSecond());
        Instant expectedExp = Instant.ofEpochSecond(EXPIRES_AT.getEpochSecond());

        assertThat(parsedIat).isEqualTo(expectedIat);
        assertThat(parsedExp).isEqualTo(expectedExp);

        assertThat(parsedExp).isBeforeOrEqualTo(EXPIRES_AT);
        assertThat(parsedIat).isBeforeOrEqualTo(ISSUED_AT);
    }

    @Test
    void signsWhenSubSecondInputsCrossIntoLaterEpochSecond() throws Exception {
        Instant iat = Instant.parse("2026-09-02T10:00:00.900Z");
        Instant exp = Instant.parse("2026-09-02T10:00:01.100Z");

        ExecutionGrantClaims claims = new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                iat,
                exp
        );

        SignedExecutionGrant grant = signer.sign(claims, testSigningKey);
        SignedJWT parsedJwt = SignedJWT.parse(grant.compactToken());
        JWTClaimsSet claimsSet = parsedJwt.getJWTClaimsSet();

        assertThat(claimsSet.getIssueTime().toInstant())
                .isEqualTo(Instant.ofEpochSecond(iat.getEpochSecond()));
        assertThat(claimsSet.getExpirationTime().toInstant())
                .isEqualTo(Instant.ofEpochSecond(exp.getEpochSecond()));
        assertThat(claimsSet.getExpirationTime().toInstant())
                .isBeforeOrEqualTo(exp);
    }

    @Test
    void failsClosedWhenSecondPrecisionValidityWindowCollapses() {
        Instant iat = Instant.parse("2026-09-02T10:00:00.100Z");
        Instant exp = Instant.parse("2026-09-02T10:00:00.900Z");

        ExecutionGrantClaims claims = new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                iat,
                exp
        );

        assertThatThrownBy(() -> signer.sign(claims, testSigningKey))
                .isInstanceOf(ExecutionGrantSigningException.class)
                .hasMessageContaining("collapsed at second precision");
    }

    @Test
    void rejectsNullClaims() {
        assertThatThrownBy(() -> signer.sign(null, testSigningKey))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("claims must not be null");
    }

    @Test
    void rejectsNullSigningKey() {
        ExecutionGrantClaims claims = validClaims();

        assertThatThrownBy(() -> signer.sign(claims, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("signingKey must not be null");
    }

    @Test
    void failsClosedWhenIncompatibleCurveKeyIsUsed() throws Exception {
        KeyPairGenerator p384Kpg = KeyPairGenerator.getInstance("EC");
        p384Kpg.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair p384KeyPair = p384Kpg.generateKeyPair();
        ExecutionGrantSigningKey p384SigningKey = new ExecutionGrantSigningKey(
                "key-p384",
                (ECPrivateKey) p384KeyPair.getPrivate()
        );

        ExecutionGrantClaims claims = validClaims();

        assertThatThrownBy(() -> signer.sign(claims, p384SigningKey))
                .isInstanceOf(ExecutionGrantSigningException.class)
                .hasMessageContaining("Failed to sign execution grant");
    }

    @Test
    void bearerTokenAndPrivateKeyAreRedactedInToString() {
        ExecutionGrantClaims claims = validClaims();

        SignedExecutionGrant grant = signer.sign(claims, testSigningKey);

        assertThat(grant.toString())
                .contains("REDACTED")
                .doesNotContain(grant.compactToken());

        assertThat(testSigningKey.toString())
                .contains(KEY_ID)
                .contains("REDACTED")
                .doesNotContain(testSigningKey.privateKey().getS().toString());
    }

    private static ExecutionGrantClaims validClaims() {
        return new ExecutionGrantClaims(
                GRANT_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                GOVERNED_ACTION_ID,
                GOVERNANCE_DECISION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                ISSUER,
                AUDIENCE,
                ISSUED_AT,
                EXPIRES_AT
        );
    }
}
