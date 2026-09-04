package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaims;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantId;
import com.proofmesh.controlplane.executiongrant.SignedExecutionGrant;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
class ExecutionGrantJwksControllerTest {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @TempDir
    static Path tempDir;

    private static final String TEST_KID = "test-jwks-kid-1";
    private static Path privKeyPath;
    private static Path pubKeyPath;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
        KeyPair kp = generateP256KeyPair();
        privKeyPath = writePem(tempDir, "jwks-test-priv.pem", toPem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        pubKeyPath = writePem(tempDir, "jwks-test-pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));

        registry.add("proofmesh.execution-grant.signing.enabled", () -> "true");
        registry.add("proofmesh.execution-grant.signing.key-id", () -> TEST_KID);
        registry.add("proofmesh.execution-grant.signing.private-key-path", () -> privKeyPath.toAbsolutePath().toString());
        registry.add("proofmesh.execution-grant.signing.public-key-path", () -> pubKeyPath.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("Enabled signing: unauthenticated GET /.well-known/jwks.json returns 200 with single active EC P-256 key")
    void jwksEndpointReturnsPublicVerificationMaterial() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=60, must-revalidate"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].crv").value("P-256"))
                .andExpect(jsonPath("$.keys[0].kid").value(TEST_KID))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("ES256"))
                .andExpect(jsonPath("$.keys[0].x").isString())
                .andExpect(jsonPath("$.keys[0].y").isString())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();

        // Strict secret safety assertions
        assertThat(responseBody).doesNotContain("\"d\"");
        assertThat(responseBody).doesNotContain("privateKey");
        assertThat(responseBody).doesNotContain("PRIVATE KEY");
        assertThat(responseBody).doesNotContain(privKeyPath.toString());
        assertThat(responseBody).doesNotContain(pubKeyPath.toString());
    }

    @Test
    @DisplayName("Interoperability: returned public JWK validates token signed by active provider with matching kid")
    void returnedPublicJwkVerifiesExecutionGrantSignature(
            @Autowired FileBasedExecutionGrantKeyProvider activeKeyProvider,
            @Autowired ExecutionGrantSigner signer
    ) throws Exception {
        // 1. Sign an execution grant using the active provider
        Instant now = Instant.now();
        ExecutionGrantClaims claims = new ExecutionGrantClaims(
                new ExecutionGrantId(UUID.randomUUID()),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ToolName("test-tool"),
                new OperationName("test-op"),
                new RequestPayloadHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                "proofmesh-control-plane",
                "proofmesh-gateway",
                now,
                now.plusSeconds(30)
        );

        SignedExecutionGrant signedGrant = signer.sign(claims, activeKeyProvider.activeSigningKey());
        SignedJWT signedJwt = SignedJWT.parse(signedGrant.compactToken());
        JWSHeader header = signedJwt.getHeader();

        // 2. Fetch JWKS
        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn();

        // 3. Parse JWKSet
        JWKSet jwkSet = JWKSet.parse(result.getResponse().getContentAsString());
        assertThat(jwkSet.getKeys()).hasSize(1);

        JWK jwk = jwkSet.getKeyByKeyId(header.getKeyID());
        assertThat(jwk).isNotNull();
        assertThat(jwk.getKeyID()).isEqualTo(header.getKeyID());
        assertThat(jwk.getKeyID()).isEqualTo(TEST_KID);
        assertThat(jwk).isInstanceOf(ECKey.class);

        ECKey ecJwk = (ECKey) jwk;
        assertThat(ecJwk.isPrivate()).isFalse();

        // 4. Verify signature using recovered public key from JWKS
        ECPublicKey recoveredPublicKey = ecJwk.toECPublicKey();
        JWSVerifier verifier = new ECDSAVerifier(recoveredPublicKey);
        assertThat(signedJwt.verify(verifier)).isTrue();
    }

    @Test
    @DisplayName("Security: exact JWKS path is permitAll; other paths fail closed")
    void securityBoundariesEnforced() throws Exception {
        // 1. Exact JWKS path allowed unauthenticated
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk());

        // 2. Unrelated .well-known paths denied
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/.well-known/other"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/.well-known"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/.well-known/"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/.well-known/jwks"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/.well-known/jwks.json/extra"))
                .andExpect(status().isUnauthorized());

        // 3. /api/** requires authentication
        mockMvc.perform(get("/api/v1/identity/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/identity/context"))
                .andExpect(status().isUnauthorized());
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(properties = "proofmesh.execution-grant.signing.enabled=false")
    @AutoConfigureMockMvc
    class SigningDisabledTest {

        @ServiceConnection
        static PostgreSQLContainer postgres = ExecutionGrantJwksControllerTest.postgres;

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("Disabled signing: JWKS controller and provider beans absent, GET returns 404")
        void signingDisabledExcludesControllerAndReturns404() throws Exception {
            // Assert beans are absent
            assertThat(applicationContext.getBeansOfType(ExecutionGrantJwksController.class)).isEmpty();
            assertThat(applicationContext.getBeansOfType(ExecutionGrantPublicKeyProvider.class)).isEmpty();
            assertThat(applicationContext.getBeansOfType(ExecutionGrantSigningKeyProvider.class)).isEmpty();

            // Assert HTTP 404 because no handler is registered
            mockMvc.perform(get("/.well-known/jwks.json"))
                    .andExpect(status().isNotFound());
        }
    }

    private static Path writePem(Path dir, String fileName, String content) throws IOException {
        Path path = dir.resolve(fileName);
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
}
