package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaimsPreparer;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantEligibilityEvaluator;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuanceResult;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuer;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

class ExecutionGrantSigningConfigurationTest {

    @TempDir
    Path tempDir;

    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ExecutionGrantConfiguration.class)
                .withPropertyValues(
                        "proofmesh.execution-grant.issuer=test-control-plane",
                        "proofmesh.execution-grant.audience=test-gateway",
                        "proofmesh.execution-grant.grant-ttl=45s"
                )
                .withBean(GovernedActionRepository.class, () -> mock(GovernedActionRepository.class))
                .withBean(GovernanceDecisionRepository.class, () -> mock(GovernanceDecisionRepository.class))
                .withBean(ApprovalRequestRepository.class, () -> mock(ApprovalRequestRepository.class));
    }

    @Test
    void defaultSigningDisabledDoesNotCreateSigningKeyProviderOrIssuer() {
        baseRunner().run(context -> {
            assertThat(context).hasNotFailed();

            // 1. no ExecutionGrantSigningKeyProvider
            assertThat(context).doesNotHaveBean(ExecutionGrantSigningKeyProvider.class);

            // 2. no ExecutionGrantIssuer
            assertThat(context).doesNotHaveBean(ExecutionGrantIssuer.class);

            // 3. existing core beans remain present
            assertThat(context).hasSingleBean(ExecutionGrantEligibilityEvaluator.class);
            assertThat(context).hasSingleBean(ExecutionGrantClaimsPreparer.class);
            assertThat(context).hasSingleBean(ExecutionGrantSigner.class);
            assertThat(context).hasSingleBean(ExecutionGrantAuthorizationContextResolver.class);
        });
    }

    @Test
    void explicitSigningDisabledIgnoresBlankOrInvalidSigningPaths() {
        baseRunner()
                .withPropertyValues(
                        "proofmesh.execution-grant.signing.enabled=false",
                        "proofmesh.execution-grant.signing.key-id=",
                        "proofmesh.execution-grant.signing.private-key-path=/nonexistent/path/priv.pem",
                        "proofmesh.execution-grant.signing.public-key-path="
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ExecutionGrantSigningKeyProvider.class);
                    assertThat(context).doesNotHaveBean(ExecutionGrantIssuer.class);
                });
    }

    @Test
    void validEnabledSigningActivatesProviderAndIssuerUsingSuppliedClock() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path privPath = writePem("prod-priv.pem", toPem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path pubPath = writePem("prod-pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));

        Clock testClock = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

        baseRunner()
                .withBean(Clock.class, () -> testClock)
                .withPropertyValues(
                        "proofmesh.execution-grant.signing.enabled=true",
                        "proofmesh.execution-grant.signing.key-id=prod-key-1",
                        "proofmesh.execution-grant.signing.private-key-path=" + privPath.toAbsolutePath(),
                        "proofmesh.execution-grant.signing.public-key-path=" + pubPath.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // exactly one signing-key provider
                    assertThat(context).hasSingleBean(ExecutionGrantSigningKeyProvider.class);
                    ExecutionGrantSigningKeyProvider provider =
                            context.getBean(ExecutionGrantSigningKeyProvider.class);
                    assertThat(provider).isInstanceOf(FileBasedExecutionGrantKeyProvider.class);
                    assertThat(provider.activeSigningKey().keyId()).isEqualTo("prod-key-1");

                    // exactly one issuer
                    assertThat(context).hasSingleBean(ExecutionGrantIssuer.class);
                    ExecutionGrantIssuer issuer = context.getBean(ExecutionGrantIssuer.class);
                    assertThat(issuer).isInstanceOf(DefaultExecutionGrantIssuer.class);

                    // exactly one Clock bean exists (the supplied testClock, no second Clock created)
                    assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
                    assertThat(context.getBean(Clock.class)).isSameAs(testClock);

                    // Prove bean is callable without full DB scenario -> StateUnavailable
                    UUID orgId = UUID.randomUUID();
                    UUID actionId = UUID.randomUUID();

                    ExecutionGrantIssuanceResult result = issuer.issue(orgId, actionId);
                    assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.StateUnavailable.class);
                });
    }

    @Test
    void enabledWithMissingPrivateFileFailsContextStartupFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path pubPath = writePem("missing-priv-pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));
        Path nonExistentPriv = tempDir.resolve("non-existent-priv.pem");

        baseRunner()
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "proofmesh.execution-grant.signing.enabled=true",
                        "proofmesh.execution-grant.signing.key-id=key-1",
                        "proofmesh.execution-grant.signing.private-key-path=" + nonExistentPriv.toAbsolutePath(),
                        "proofmesh.execution-grant.signing.public-key-path=" + pubPath.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Failed to read private key file at");
                });
    }

    @Test
    void enabledWithMalformedKeyFailsContextStartupFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path pubPath = writePem("malformed-pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));
        Path malformedPriv = writePem("malformed-priv.pem", "-----BEGIN PRIVATE KEY-----\nINVALID_BASE64!@#\n-----END PRIVATE KEY-----\n");

        baseRunner()
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "proofmesh.execution-grant.signing.enabled=true",
                        "proofmesh.execution-grant.signing.key-id=key-1",
                        "proofmesh.execution-grant.signing.private-key-path=" + malformedPriv.toAbsolutePath(),
                        "proofmesh.execution-grant.signing.public-key-path=" + pubPath.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Malformed Base64 in private key file");
                });
    }

    @Test
    void enabledWithP384KeyFailsContextStartupFast() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair p384Pair = kpg.generateKeyPair();

        Path privPath = writePem("p384-priv.pem", toPem("PRIVATE KEY", p384Pair.getPrivate().getEncoded()));
        Path pubPath = writePem("p384-pub.pem", toPem("PUBLIC KEY", p384Pair.getPublic().getEncoded()));

        baseRunner()
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "proofmesh.execution-grant.signing.enabled=true",
                        "proofmesh.execution-grant.signing.key-id=key-1",
                        "proofmesh.execution-grant.signing.private-key-path=" + privPath.toAbsolutePath(),
                        "proofmesh.execution-grant.signing.public-key-path=" + pubPath.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("does not use curve P-256 (secp256r1)");
                });
    }

    @Test
    void enabledWithMismatchedP256PairFailsContextStartupFast() throws Exception {
        KeyPair kp1 = generateP256KeyPair();
        KeyPair kp2 = generateP256KeyPair();

        Path privPath1 = writePem("mismatch-priv.pem", toPem("PRIVATE KEY", kp1.getPrivate().getEncoded()));
        Path pubPath2 = writePem("mismatch-pub.pem", toPem("PUBLIC KEY", kp2.getPublic().getEncoded()));

        baseRunner()
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "proofmesh.execution-grant.signing.enabled=true",
                        "proofmesh.execution-grant.signing.key-id=key-1",
                        "proofmesh.execution-grant.signing.private-key-path=" + privPath1.toAbsolutePath(),
                        "proofmesh.execution-grant.signing.public-key-path=" + pubPath2.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("do not form a matching key pair");
                });
    }

    @Test
    void enabledWithBlankKeyIdOrPathFailsContextStartupFast() throws Exception {
        KeyPair kp = generateP256KeyPair();
        Path privPath = writePem("blank-prop-priv.pem", toPem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path pubPath = writePem("blank-prop-pub.pem", toPem("PUBLIC KEY", kp.getPublic().getEncoded()));

        baseRunner()
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "proofmesh.execution-grant.signing.enabled=true",
                        "proofmesh.execution-grant.signing.key-id=   ",
                        "proofmesh.execution-grant.signing.private-key-path=" + privPath.toAbsolutePath(),
                        "proofmesh.execution-grant.signing.public-key-path=" + pubPath.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .rootCause()
                            .hasMessageContaining("keyId must not be blank");
                });
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
}
