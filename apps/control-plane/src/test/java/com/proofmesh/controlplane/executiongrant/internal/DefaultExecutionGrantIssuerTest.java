package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantAuthorizationContext;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaims;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaimsPreparer;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantEligibility;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantEligibilityEvaluator;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantId;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIdGenerator;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuancePolicy;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuanceResult;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuer;
import com.proofmesh.controlplane.executiongrant.SignedExecutionGrant;
import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class DefaultExecutionGrantIssuerTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000001");

    private static final UUID AGENT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000001");

    private static final UUID ACTION_ID =
            UUID.fromString("73000000-0000-0000-0000-000000000001");

    private static final UUID DECISION_ID =
            UUID.fromString("74000000-0000-0000-0000-000000000001");

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString("75000000-0000-0000-0000-000000000001");

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(UUID.fromString("76000000-0000-0000-0000-000000000001"));

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(UUID.fromString("77000000-0000-0000-0000-000000000001"));

    private static final ToolName TOOL_NAME =
            new ToolName("stripe");

    private static final OperationName OPERATION_NAME =
            new OperationName("refund_payment");

    private static final RequestPayloadHash REQUEST_PAYLOAD_HASH =
            new RequestPayloadHash("a".repeat(64));

    private static final String ISSUER = "proofmesh-control-plane";
    private static final String AUDIENCE = "proofmesh-gateway";
    private static final Duration GRANT_TTL = Duration.ofSeconds(30);

    private static final Instant T0 = Instant.parse("2026-09-02T10:00:00.000Z");

    private static KeyPair testKeyPair;
    private static ExecutionGrantSigningKey testSigningKey;

    private ScriptableClock clock;
    private ExecutionGrantClaimsPreparer claimsPreparer;
    private TrackingSigner trackingSigner;
    private TrackingKeyProvider trackingKeyProvider;
    private StubContextResolver contextResolver;
    private DefaultExecutionGrantIssuer issuer;

    @BeforeAll
    static void initCrypto() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        testKeyPair = generator.generateKeyPair();
        testSigningKey = new ExecutionGrantSigningKey(
                "test-key-01",
                (ECPrivateKey) testKeyPair.getPrivate()
        );
    }

    @BeforeEach
    void setUp() {
        clock = new ScriptableClock(T0);
        ExecutionGrantEligibilityEvaluator evaluator = new ExecutionGrantEligibilityEvaluator();
        ExecutionGrantIdGenerator idGenerator = () -> new ExecutionGrantId(
                UUID.fromString("70000000-0000-0000-0000-000000000001")
        );
        ExecutionGrantIssuancePolicy policy = new ExecutionGrantIssuancePolicy(
                ISSUER,
                AUDIENCE,
                GRANT_TTL
        );
        claimsPreparer = new ExecutionGrantClaimsPreparer(
                evaluator,
                idGenerator,
                policy
        );

        trackingSigner = new TrackingSigner(new NimbusExecutionGrantSigner());
        trackingKeyProvider = new TrackingKeyProvider(testSigningKey);
        contextResolver = new StubContextResolver();

        issuer = new DefaultExecutionGrantIssuer(
                contextResolver,
                claimsPreparer,
                trackingKeyProvider,
                trackingSigner,
                clock
        );
    }

    // --- Constructor & Input Validations ---

    @Test
    void rejectsNullConstructorDependencies() {
        assertThatThrownBy(() -> new DefaultExecutionGrantIssuer(
                null, claimsPreparer, trackingKeyProvider, trackingSigner, clock))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("contextResolver must not be null");

        assertThatThrownBy(() -> new DefaultExecutionGrantIssuer(
                contextResolver, null, trackingKeyProvider, trackingSigner, clock))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("claimsPreparer must not be null");

        assertThatThrownBy(() -> new DefaultExecutionGrantIssuer(
                contextResolver, claimsPreparer, null, trackingSigner, clock))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("signingKeyProvider must not be null");

        assertThatThrownBy(() -> new DefaultExecutionGrantIssuer(
                contextResolver, claimsPreparer, trackingKeyProvider, null, clock))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("signer must not be null");

        assertThatThrownBy(() -> new DefaultExecutionGrantIssuer(
                contextResolver, claimsPreparer, trackingKeyProvider, trackingSigner, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock must not be null");
    }

    @Test
    void rejectsNullOrganizationId() {
        assertThatThrownBy(() -> issuer.issue(null, ACTION_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("organizationId must not be null");
    }

    @Test
    void rejectsNullGovernedActionId() {
        assertThatThrownBy(() -> issuer.issue(ORGANIZATION_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("governedActionId must not be null");
    }

    // --- Public Boundary & Fabricated-Object Resistance ---

    @Test
    void publicIssuerAcceptsIdentifiersOnlyAndHasNoFabricatedObjectOverloads() {
        Method[] methods = ExecutionGrantIssuer.class.getMethods();
        assertThat(methods).hasSize(1);

        Method issueMethod = methods[0];
        assertThat(issueMethod.getName()).isEqualTo("issue");
        assertThat(issueMethod.getParameterTypes()).containsExactly(UUID.class, UUID.class);

        List<Class<?>> forbiddenParameterTypes = List.of(
                GovernedAction.class,
                GovernanceDecision.class,
                ApprovalRequest.class,
                ExecutionGrantAuthorizationContext.class,
                ExecutionGrantClaims.class,
                Instant.class,
                Clock.class,
                ExecutionGrantSigningKey.class
        );

        for (Method method : ExecutionGrantIssuer.class.getDeclaredMethods()) {
            for (Class<?> paramType : method.getParameterTypes()) {
                assertThat(forbiddenParameterTypes)
                        .as("Public interface must not accept fabricated type: " + paramType.getSimpleName())
                        .doesNotContain(paramType);
            }
        }
    }

    // --- State Unavailable ---

    @Test
    void returnsStateUnavailableWhenResolverReturnsEmpty() {
        contextResolver.setContext(null);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result)
                .isInstanceOf(ExecutionGrantIssuanceResult.StateUnavailable.class);
        ExecutionGrantIssuanceResult.StateUnavailable unavailable =
                (ExecutionGrantIssuanceResult.StateUnavailable) result;
        assertThat(unavailable.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(unavailable.governedActionId()).isEqualTo(ACTION_ID);

        assertThat(clock.readCount()).isZero();
        assertThat(trackingKeyProvider.callCount()).isZero();
        assertThat(trackingSigner.signCount()).isZero();
    }

    // --- ALLOW Success Path ---

    @Test
    void successfullyIssuesGrantForAuthoritativeAllow() throws Exception {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);
        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(action, decision, Optional.empty());
        contextResolver.setContext(context);

        clock.enqueue(T0, T0.plusMillis(50));

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Issued.class);
        ExecutionGrantIssuanceResult.Issued issued = (ExecutionGrantIssuanceResult.Issued) result;
        SignedExecutionGrant grant = issued.grant();
        assertThat(grant).isNotNull();
        assertThat(grant.compactToken()).isNotBlank();

        // Verify token signature with public key
        SignedJWT signedJWT = SignedJWT.parse(grant.compactToken());
        boolean verified = signedJWT.verify(new ECDSAVerifier((ECPublicKey) testKeyPair.getPublic()));
        assertThat(verified).isTrue();

        // Verify token claims bound to authoritative action & decision
        JWTClaimsSet claimsSet = signedJWT.getJWTClaimsSet();
        assertThat(claimsSet.getStringClaim("org_id")).isEqualTo(ORGANIZATION_ID.toString());
        assertThat(claimsSet.getStringClaim("agent_id")).isEqualTo(AGENT_ID.toString());
        assertThat(claimsSet.getStringClaim("action_id")).isEqualTo(ACTION_ID.toString());
        assertThat(claimsSet.getStringClaim("decision_id")).isEqualTo(DECISION_ID.toString());
        assertThat(claimsSet.getStringClaim("tool_name")).isEqualTo(TOOL_NAME.value());
        assertThat(claimsSet.getStringClaim("operation_name")).isEqualTo(OPERATION_NAME.value());
        assertThat(claimsSet.getStringClaim("payload_hash")).isEqualTo(REQUEST_PAYLOAD_HASH.value());
        assertThat(claimsSet.getIssuer()).isEqualTo(ISSUER);
        assertThat(claimsSet.getAudience()).containsExactly(AUDIENCE);
        assertThat(claimsSet.getIssueTime().toInstant()).isEqualTo(Instant.ofEpochSecond(T0.getEpochSecond()));

        // Exactly two clock reads on successful flow
        assertThat(clock.readCount()).isEqualTo(2);

        // Key provider called exactly once
        assertThat(trackingKeyProvider.callCount()).isEqualTo(1);

        // Raw signer invoked exactly once
        assertThat(trackingSigner.signCount()).isEqualTo(1);

        // Verify Issued result does not expose ExecutionGrantClaims in public record
        RecordComponent[] components = ExecutionGrantIssuanceResult.Issued.class.getRecordComponents();
        assertThat(components).hasSize(1);
        assertThat(components[0].getType()).isEqualTo(SignedExecutionGrant.class);
    }

    // --- REQUIRE_APPROVAL Success Path ---

    @Test
    void successfullyIssuesGrantForAuthoritativeApprovedRequestWithApprovalCap() throws Exception {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.REQUIRE_APPROVAL);
        // Approval expires in 10 seconds (shorter than policy TTL of 30 seconds)
        Instant approvalExpiresAt = T0.plusSeconds(10);
        ApprovalRequest approval = approvalRequest(ApprovalState.Approved.class, approvalExpiresAt);
        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(action, decision, Optional.of(approval));
        contextResolver.setContext(context);

        clock.enqueue(T0, T0.plusMillis(100));

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Issued.class);
        ExecutionGrantIssuanceResult.Issued issued = (ExecutionGrantIssuanceResult.Issued) result;

        SignedJWT signedJWT = SignedJWT.parse(issued.grant().compactToken());
        assertThat(signedJWT.verify(new ECDSAVerifier((ECPublicKey) testKeyPair.getPublic()))).isTrue();

        // JWT expiration capped by approval request expiration
        assertThat(signedJWT.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(Instant.ofEpochSecond(approvalExpiresAt.getEpochSecond()));
    }

    // --- Normal Ineligibility Outcomes ---

    @Test
    void returnsIneligibleForDeniedDecisionWithoutKeyAcquisitionOrSigning() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.DENY);
        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(action, decision, Optional.empty());
        contextResolver.setContext(context);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        ExecutionGrantIssuanceResult.Ineligible ineligible =
                (ExecutionGrantIssuanceResult.Ineligible) result;
        assertThat(ineligible.reason()).isEqualTo(ExecutionGrantEligibility.Reason.DECISION_DENIED);

        assertThat(clock.readCount()).isEqualTo(1);
        assertThat(trackingKeyProvider.callCount()).isZero();
        assertThat(trackingSigner.signCount()).isZero();
    }

    @Test
    void returnsIneligibleWhenDecisionRequiresApprovalAndApprovalIsMissing() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.REQUIRE_APPROVAL);
        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(action, decision, Optional.empty());
        contextResolver.setContext(context);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        ExecutionGrantIssuanceResult.Ineligible ineligible =
                (ExecutionGrantIssuanceResult.Ineligible) result;
        assertThat(ineligible.reason()).isEqualTo(ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID);

        assertThat(clock.readCount()).isEqualTo(1);
        assertThat(trackingKeyProvider.callCount()).isZero();
        assertThat(trackingSigner.signCount()).isZero();
    }

    @Test
    void returnsIneligibleWhenApprovalIsPending() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.REQUIRE_APPROVAL);
        ApprovalRequest approval = approvalRequest(ApprovalState.Pending.class, T0.plus(Duration.ofMinutes(15)));
        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(action, decision, Optional.of(approval));
        contextResolver.setContext(context);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        ExecutionGrantIssuanceResult.Ineligible ineligible =
                (ExecutionGrantIssuanceResult.Ineligible) result;
        assertThat(ineligible.reason()).isEqualTo(ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID);

        assertThat(clock.readCount()).isEqualTo(1);
        assertThat(trackingKeyProvider.callCount()).isZero();
        assertThat(trackingSigner.signCount()).isZero();
    }

    @Test
    void returnsIneligibleWhenApprovalIsRejected() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.REQUIRE_APPROVAL);
        ApprovalRequest approval = approvalRequest(ApprovalState.Rejected.class, T0.plus(Duration.ofMinutes(15)));
        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(action, decision, Optional.of(approval));
        contextResolver.setContext(context);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        ExecutionGrantIssuanceResult.Ineligible ineligible =
                (ExecutionGrantIssuanceResult.Ineligible) result;
        assertThat(ineligible.reason()).isEqualTo(ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID);

        assertThat(clock.readCount()).isEqualTo(1);
        assertThat(trackingKeyProvider.callCount()).isZero();
        assertThat(trackingSigner.signCount()).isZero();
    }

    @Test
    void returnsIneligibleWhenApprovalIsExpiredAtAuthorizationTime() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.REQUIRE_APPROVAL);
        // Approval expired 1 second before authorization time T0
        Instant expiredAt = T0.minusSeconds(1);
        ApprovalRequest approval = approvalRequest(ApprovalState.Approved.class, expiredAt);
        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(action, decision, Optional.of(approval));
        contextResolver.setContext(context);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        ExecutionGrantIssuanceResult.Ineligible ineligible =
                (ExecutionGrantIssuanceResult.Ineligible) result;
        assertThat(ineligible.reason()).isEqualTo(ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID);

        assertThat(clock.readCount()).isEqualTo(1);
        assertThat(trackingKeyProvider.callCount()).isZero();
        assertThat(trackingSigner.signCount()).isZero();
    }

    // --- Freshness & Stale Authorization ---

    @Test
    void signsWhenSigningNowIsStrictlyBeforeEffectiveWholeSecondExpiration() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);
        contextResolver.setContext(new ExecutionGrantAuthorizationContext(action, decision, Optional.empty()));

        // T0 = 10:00:00.000Z -> policyExpiresAt = 10:00:30.000Z -> effectiveExpiration = 10:00:30.000Z
        // signingNow = 10:00:29.999Z (strictly before effectiveExpiration)
        Instant signingNow = Instant.parse("2026-09-02T10:00:29.999Z");
        clock.enqueue(T0, signingNow);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Issued.class);
        assertThat(trackingSigner.signCount()).isEqualTo(1);
    }

    @Test
    void returnsStaleAuthorizationWhenSigningNowEqualsEffectiveWholeSecondExpiration() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);
        contextResolver.setContext(new ExecutionGrantAuthorizationContext(action, decision, Optional.empty()));

        // T0 = 10:00:00.800Z -> expiresAt = 10:00:30.800Z -> effectiveExpiration = 10:00:30.000Z
        Instant authNow = Instant.parse("2026-09-02T10:00:00.800Z");
        Instant signingNow = Instant.parse("2026-09-02T10:00:30.000Z");
        Instant expectedEffectiveExp = Instant.parse("2026-09-02T10:00:30.000Z");

        clock.enqueue(authNow, signingNow);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.StaleAuthorization.class);
        ExecutionGrantIssuanceResult.StaleAuthorization stale =
                (ExecutionGrantIssuanceResult.StaleAuthorization) result;
        assertThat(stale.attemptedAt()).isEqualTo(signingNow);
        assertThat(stale.effectiveExpiration()).isEqualTo(expectedEffectiveExp);

        // Signer must not be invoked on stale path
        assertThat(trackingSigner.signCount()).isZero();
    }

    @Test
    void returnsStaleAuthorizationWhenSigningNowIsAfterEffectiveWholeSecondExpiration() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);
        contextResolver.setContext(new ExecutionGrantAuthorizationContext(action, decision, Optional.empty()));

        Instant authNow = Instant.parse("2026-09-02T10:00:00.000Z");
        Instant signingNow = Instant.parse("2026-09-02T10:00:30.001Z");
        Instant expectedEffectiveExp = Instant.parse("2026-09-02T10:00:30.000Z");

        clock.enqueue(authNow, signingNow);

        ExecutionGrantIssuanceResult result = issuer.issue(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.StaleAuthorization.class);
        ExecutionGrantIssuanceResult.StaleAuthorization stale =
                (ExecutionGrantIssuanceResult.StaleAuthorization) result;
        assertThat(stale.attemptedAt()).isEqualTo(signingNow);
        assertThat(stale.effectiveExpiration()).isEqualTo(expectedEffectiveExp);

        assertThat(trackingSigner.signCount()).isZero();
    }

    @Test
    void keyProviderLatencyConsumesAuthorizationLifetimeBecauseKeyAcquisitionOccursBeforeSecondClockRead() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);
        contextResolver.setContext(new ExecutionGrantAuthorizationContext(action, decision, Optional.empty()));

        List<String> callSequence = new ArrayList<>();

        TrackingKeyProvider providerWithLatencyTracking = new TrackingKeyProvider(testSigningKey) {
            @Override
            public ExecutionGrantSigningKey activeSigningKey() {
                callSequence.add("KEY_PROVIDER");
                return super.activeSigningKey();
            }
        };

        ScriptableClock sequencedClock = new ScriptableClock(T0) {
            @Override
            public Instant instant() {
                Instant result = super.instant();
                callSequence.add("CLOCK_" + readCount());
                return result;
            }
        };

        DefaultExecutionGrantIssuer sequencedIssuer = new DefaultExecutionGrantIssuer(
                contextResolver,
                claimsPreparer,
                providerWithLatencyTracking,
                trackingSigner,
                sequencedClock
        );

        sequencedIssuer.issue(ORGANIZATION_ID, ACTION_ID);

        // Sequence must be: CLOCK_1 (authNow), KEY_PROVIDER, CLOCK_2 (signingNow)
        assertThat(callSequence).containsExactly(
                "CLOCK_1",
                "KEY_PROVIDER",
                "CLOCK_2"
        );
    }

    // --- Failure Handling ---

    @Test
    void propagatesSigningFailureAndReturnsNoIssuedResult() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);
        contextResolver.setContext(new ExecutionGrantAuthorizationContext(action, decision, Optional.empty()));

        ExecutionGrantSigner failingSigner = (claims, key) -> {
            throw new ExecutionGrantSigningException("Signing hardware failed");
        };

        DefaultExecutionGrantIssuer failingIssuer = new DefaultExecutionGrantIssuer(
                contextResolver,
                claimsPreparer,
                trackingKeyProvider,
                failingSigner,
                clock
        );

        assertThatThrownBy(() -> failingIssuer.issue(ORGANIZATION_ID, ACTION_ID))
                .isInstanceOf(ExecutionGrantSigningException.class)
                .hasMessage("Signing hardware failed");
    }

    @Test
    void failsClosedWhenKeyProviderReturnsNull() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);
        contextResolver.setContext(new ExecutionGrantAuthorizationContext(action, decision, Optional.empty()));

        ExecutionGrantSigningKeyProvider nullProvider = () -> null;

        DefaultExecutionGrantIssuer nullKeyIssuer = new DefaultExecutionGrantIssuer(
                contextResolver,
                claimsPreparer,
                nullProvider,
                trackingSigner,
                clock
        );

        assertThatThrownBy(() -> nullKeyIssuer.issue(ORGANIZATION_ID, ACTION_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("activeSigningKey must not be null");
    }

    @Test
    void failsClosedWhenSignerReturnsNull() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);
        contextResolver.setContext(new ExecutionGrantAuthorizationContext(action, decision, Optional.empty()));

        ExecutionGrantSigner nullSigner = (claims, key) -> null;

        DefaultExecutionGrantIssuer nullSignerIssuer = new DefaultExecutionGrantIssuer(
                contextResolver,
                claimsPreparer,
                trackingKeyProvider,
                nullSigner,
                clock
        );

        assertThatThrownBy(() -> nullSignerIssuer.issue(ORGANIZATION_ID, ACTION_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("signedGrant must not be null");
    }

    // --- Result Record Validations ---

    @Test
    void resultRecordsRejectNullFields() {
        assertThatThrownBy(() -> new ExecutionGrantIssuanceResult.Issued(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("grant must not be null");

        assertThatThrownBy(() -> new ExecutionGrantIssuanceResult.Ineligible(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reason must not be null");

        assertThatThrownBy(() -> new ExecutionGrantIssuanceResult.StateUnavailable(null, ACTION_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("organizationId must not be null");

        assertThatThrownBy(() -> new ExecutionGrantIssuanceResult.StateUnavailable(ORGANIZATION_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("governedActionId must not be null");

        assertThatThrownBy(() -> new ExecutionGrantIssuanceResult.StaleAuthorization(null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("attemptedAt must not be null");

        assertThatThrownBy(() -> new ExecutionGrantIssuanceResult.StaleAuthorization(T0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("effectiveExpiration must not be null");
    }

    // --- Test Helpers & Fakes ---

    private static GovernedAction governedAction() {
        return new GovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey("issuer-test-key"),
                TOOL_NAME,
                OPERATION_NAME,
                new CanonicalRequestPayload("{\"amount\":100}", REQUEST_PAYLOAD_HASH),
                T0
        );
    }

    private static GovernanceDecision governanceDecision(DecisionOutcome outcome) {
        PolicyRuleId matchedRule = outcome == DecisionOutcome.DENY ? null : POLICY_RULE_ID;
        return new GovernanceDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                matchedRule,
                outcome,
                new RiskScore(50),
                List.of(new DecisionReasonCode("REASON_CODE")),
                T0
        );
    }

    private static ApprovalRequest approvalRequest(
            Class<? extends ApprovalState> stateClass,
            Instant expiresAt
    ) {
        Instant requestedAt = T0.minusSeconds(60);
        Instant decidedAt = requestedAt.plusSeconds(10);
        ApprovalState state;
        if (stateClass == ApprovalState.Approved.class) {
            state = new ApprovalState.Approved(
                    new ApprovalActorId("operator-001"),
                    new ApprovalRationale("Approved"),
                    decidedAt
            );
        } else if (stateClass == ApprovalState.Rejected.class) {
            state = new ApprovalState.Rejected(
                    new ApprovalActorId("operator-001"),
                    new ApprovalRationale("Rejected"),
                    decidedAt
            );
        } else {
            state = new ApprovalState.Pending();
        }

        return new ApprovalRequest(
                APPROVAL_REQUEST_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                DECISION_ID,
                AGENT_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                requestedAt,
                expiresAt,
                state
        );
    }

    private static class StubContextResolver
            implements ExecutionGrantAuthorizationContextResolver {

        private ExecutionGrantAuthorizationContext context;

        void setContext(ExecutionGrantAuthorizationContext context) {
            this.context = context;
        }

        @Override
        public Optional<ExecutionGrantAuthorizationContext> resolve(
                UUID organizationId,
                UUID governedActionId
        ) {
            return Optional.ofNullable(context);
        }
    }

    private static class TrackingKeyProvider
            implements ExecutionGrantSigningKeyProvider {

        private final ExecutionGrantSigningKey key;
        private final AtomicInteger calls = new AtomicInteger();

        TrackingKeyProvider(ExecutionGrantSigningKey key) {
            this.key = key;
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public ExecutionGrantSigningKey activeSigningKey() {
            calls.incrementAndGet();
            return key;
        }
    }

    private static class TrackingSigner implements ExecutionGrantSigner {

        private final ExecutionGrantSigner delegate;
        private final AtomicInteger signs = new AtomicInteger();

        TrackingSigner(ExecutionGrantSigner delegate) {
            this.delegate = delegate;
        }

        int signCount() {
            return signs.get();
        }

        @Override
        public SignedExecutionGrant sign(
                ExecutionGrantClaims claims,
                ExecutionGrantSigningKey signingKey
        ) {
            signs.incrementAndGet();
            return delegate.sign(claims, signingKey);
        }
    }

    private static class ScriptableClock extends Clock {

        private final ZoneId zone = ZoneOffset.UTC;
        private final Queue<Instant> queue = new ArrayDeque<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final Instant defaultInstant;

        ScriptableClock(Instant defaultInstant) {
            this.defaultInstant = defaultInstant;
        }

        void enqueue(Instant... instants) {
            queue.addAll(Arrays.asList(instants));
        }

        int readCount() {
            return reads.get();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            reads.incrementAndGet();
            if (!queue.isEmpty()) {
                return queue.poll();
            }
            return defaultInstant;
        }
    }
}
