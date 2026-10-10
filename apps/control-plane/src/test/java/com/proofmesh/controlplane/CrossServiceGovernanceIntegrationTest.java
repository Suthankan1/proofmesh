package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.approval.*;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.executiongrant.*;
import com.proofmesh.controlplane.governedaction.*;
import com.proofmesh.controlplane.policy.*;
import com.proofmesh.controlplane.runtimegovernance.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in cross-language proof; does not bypass or implement agent authentication. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "proofmesh.crossservice", matches = "true")
class CrossServiceGovernanceIntegrationTest {
    private static Path keyDirectory;
    private static final String PAYLOAD = "{\"amount\":5000,\"paymentId\":\"local-test\"}";
    private final UUID organizationId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private HttpServer tool;
    private Process gateway;
    private URI executionUri;

    @Autowired JdbcTemplate jdbc;
    @Autowired GovernedActionRepository actions;
    @Autowired RequestPayloadCanonicalizer canonicalizer;
    @Autowired PolicyVersionRepository versions;
    @Autowired PolicyPublisher publisher;
    @Autowired RuntimeGovernanceOrchestrator governance;
    @Autowired ExecutionGrantIssuer issuer;
    @Autowired ApprovalRequestResolver approvals;
    @Autowired PostgreSQLContainer postgres;
    @LocalServerPort int springPort;

    @DynamicPropertySource
    static void signing(DynamicPropertyRegistry registry) throws Exception {
        keyDirectory = Files.createTempDirectory("proofmesh-crossservice-keys-");
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var keys = generator.generateKeyPair();
        var privatePath = keyDirectory.resolve("private.pem");
        var publicPath = keyDirectory.resolve("public.pem");
        Files.writeString(privatePath, pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        Files.writeString(publicPath, pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        registry.add("proofmesh.execution-grant.signing.enabled", () -> "true");
        registry.add("proofmesh.execution-grant.signing.key-id", () -> "crossservice-test");
        registry.add("proofmesh.execution-grant.signing.private-key-path", privatePath::toString);
        registry.add("proofmesh.execution-grant.signing.public-key-path", publicPath::toString);
    }

    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(bytes) + "\n-----END " + label + "-----\n";
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",
                organizationId, organizationId.toString(), "Cross-service test");
        jdbc.update("INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES (?,?,?,'ACTIVE')",
                agentId, organizationId, "Local agent");
        tool = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tool.createContext("/refund", exchange -> {
            toolCalls.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            byte[] reply = Arrays.equals(body, PAYLOAD.getBytes(StandardCharsets.UTF_8))
                    ? "accepted".getBytes(StandardCharsets.UTF_8) : "wrong-payload".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try (var output = exchange.getResponseBody()) { output.write(reply); }
            exchange.close();
        });
        tool.start();
        startGateway();
    }

    private void startGateway() throws Exception {
        String binary = Objects.requireNonNull(System.getenv("PROOFMESH_CROSSSERVICE_BINARY"),
                "Run scripts/test-local-governance.sh");
        var builder = new ProcessBuilder(binary).redirectError(ProcessBuilder.Redirect.DISCARD);
        var env = builder.environment();
        env.keySet().removeIf(key -> key.startsWith("OTEL_") || key.startsWith("PROOFMESH_"));
        env.put("PROOFMESH_DATABASE_URL", "postgresql://" + postgres.getUsername() + ":" + postgres.getPassword()
                + "@" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName()
                + "?sslmode=disable");
        env.put("PROOFMESH_EXECUTION_GRANT_ISSUER", "proofmesh-control-plane");
        env.put("PROOFMESH_EXECUTION_GRANT_AUDIENCE", "proofmesh-gateway");
        env.put("PROOFMESH_JWKS_URL", "http://127.0.0.1:" + springPort + "/.well-known/jwks.json");
        env.put("PROOFMESH_TOOL_TARGETS_JSON", "[{\"tool_name\":\"payments\",\"operation_name\":\"refund\",\"url\":\"http://127.0.0.1:"
                + tool.getAddress().getPort() + "/refund\"}]");
        gateway = builder.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var ready = executor.submit(() -> new BufferedReader(new InputStreamReader(gateway.getInputStream())).readLine());
            try {
                String address = ready.get(35, TimeUnit.SECONDS);
                assertThat(address).startsWith("http://127.0.0.1:");
                executionUri = URI.create(address + "/v1/executions");
            } catch (Exception failure) {
                stopGateway();
                throw failure;
            }
        }
    }

    private void stopGateway() throws Exception {
        if (gateway == null) return;
        gateway.destroy();
        if (!gateway.waitFor(8, TimeUnit.SECONDS)) {
            gateway.destroyForcibly();
            assertThat(gateway.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
        gateway = null;
    }

    @AfterEach
    void cleanup() throws Exception {
        try { stopGateway(); } finally { if (tool != null) tool.stop(0); }
    }

    @AfterAll
    static void removeKeys() throws Exception {
        if (keyDirectory != null) {
            Files.deleteIfExists(keyDirectory.resolve("private.pem"));
            Files.deleteIfExists(keyDirectory.resolve("public.pem"));
            Files.deleteIfExists(keyDirectory);
        }
    }

    @Test
    void springAllowExecutesOnceAndReplaySurvivesGatewayRestart() throws Exception {
        var action = actionWithPolicy(PolicyEffect.ALLOW);
        var governed = govern(action, Instant.now());
        assertThat(governed.decision().outcome()).isEqualTo(DecisionOutcome.ALLOW);
        String token = token(action);
        assertThat(execute(action, governed, token, "{\"paymentId\":\"local-test\",\"amount\":5000}").body())
                .isEqualTo("accepted");
        assertThat(toolCalls).hasValue(1);
        assertThat(execute(action, governed, token, PAYLOAD).statusCode()).isEqualTo(409);
        stopGateway();
        startGateway();
        assertThat(execute(action, governed, token, PAYLOAD).statusCode()).isEqualTo(409);
        assertThat(toolCalls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.execution_grant_claims WHERE governed_action_id=?",
                Integer.class, action.id())).isEqualTo(1);
    }

    @Test
    void approvedExactRetryExecutesButChangedPayloadCannotConsumeGrant() throws Exception {
        var action = actionWithPolicy(PolicyEffect.REQUIRE_APPROVAL);
        var governed = govern(action, Instant.now());
        var pending = governed.approvalRequest().orElseThrow();
        assertThat(issuer.issue(organizationId, action.id())).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        assertThat(execute(action, governed, "absent-grant", PAYLOAD).statusCode()).isEqualTo(401);
        approvals.approve(organizationId, pending.id(), new ApprovalActorId("crossservice-approver"),
                new ApprovalRationale("Reviewed exact local test action"), Instant.now());
        assertThat(toolCalls).hasValue(0);
        var retry = govern(action, Instant.now());
        assertThat(retry.decision().id()).isEqualTo(governed.decision().id());
        assertThat(retry.decision().outcome()).isEqualTo(DecisionOutcome.REQUIRE_APPROVAL);
        String token = token(action);
        assertThat(execute(action, retry, token, "{\"amount\":5001,\"paymentId\":\"local-test\"}").statusCode()).isEqualTo(403);
        assertThat(toolCalls).hasValue(0);
        assertThat(execute(action, retry, token, PAYLOAD).statusCode()).isEqualTo(200);
        assertThat(toolCalls).hasValue(1);
    }

    @Test
    void denyCannotMintAuthorityOrInvokeTool() throws Exception {
        var action = actionWithPolicy(PolicyEffect.DENY);
        var denied = govern(action, Instant.now());
        assertThat(denied.decision().outcome()).isEqualTo(DecisionOutcome.DENY);
        assertThat(issuer.issue(organizationId, action.id())).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        assertThat(execute(action, denied, "absent-grant", PAYLOAD).statusCode()).isEqualTo(401);
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void rejectedApprovalCannotMintAuthority() throws Exception {
        var action = actionWithPolicy(PolicyEffect.REQUIRE_APPROVAL);
        var governed = govern(action, Instant.now());
        approvals.reject(organizationId, governed.approvalRequest().orElseThrow().id(),
                new ApprovalActorId("crossservice-approver"), new ApprovalRationale("Rejected local test"), Instant.now());
        assertThat(issuer.issue(organizationId, action.id())).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        assertThat(execute(action, governed, "absent-grant", PAYLOAD).statusCode()).isEqualTo(401);
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void historicallyApprovedButTimeExpiredCannotMintAuthority() throws Exception {
        var action = actionWithPolicy(PolicyEffect.REQUIRE_APPROVAL);
        Instant past = Instant.now().minus(Duration.ofDays(1));
        var governed = govern(action, past);
        var approved = approvals.approve(organizationId, governed.approvalRequest().orElseThrow().id(),
                new ApprovalActorId("crossservice-approver"), new ApprovalRationale("Historical local test"), past.plusSeconds(1));
        assertThat(approved.isApproved()).isTrue();
        assertThat(approved.isApprovedAndValidAt(Instant.now())).isFalse();
        assertThat(issuer.issue(organizationId, action.id())).isInstanceOf(ExecutionGrantIssuanceResult.Ineligible.class);
        assertThat(toolCalls).hasValue(0);
    }

    private GovernedAction actionWithPolicy(PolicyEffect effect) {
        Instant created = Instant.now().minus(Duration.ofDays(2));
        UUID policy = UUID.randomUUID();
        jdbc.update("INSERT INTO proofmesh.policies(id,organization_id,name,created_at) VALUES (?,?,?,?)",
                policy, organizationId, "Local test policy", OffsetDateTime.ofInstant(created, ZoneOffset.UTC));
        var definition = new PolicyDefinition(List.of(new PolicyRule(new PolicyRuleId(UUID.randomUUID()),
                new PolicyRulePriority(100), new PolicyTarget("payments", "refund"), new PolicyRiskThreshold(0),
                effect, new PolicyReasonCode("LOCAL_TEST"))));
        var draft = new PolicyVersion(new PolicyVersionId(UUID.randomUUID()), policy, organizationId,
                new PolicyVersionNumber(1), PolicyVersionState.DRAFT, definition, null, created, null);
        versions.insertDraft(draft);
        versions.persistPublication(publisher.publish(draft, created.plusSeconds(1)));
        jdbc.update("INSERT INTO proofmesh.agent_policy_bindings(id,organization_id,agent_id,policy_version_id,activated_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), organizationId, agentId, draft.id().value(), OffsetDateTime.ofInstant(created.plusSeconds(2), ZoneOffset.UTC));
        var action = new GovernedAction(UUID.randomUUID(), organizationId, agentId,
                new IdempotencyKey(UUID.randomUUID().toString()), new ToolName("payments"), new OperationName("refund"),
                canonicalizer.canonicalize(PAYLOAD), created.plusSeconds(3));
        actions.insertIfAbsent(action);
        return action;
    }

    private RuntimeGovernanceResult.Governed govern(GovernedAction action, Instant at) {
        var result = governance.govern(new RuntimeGovernanceRequest(UUID.randomUUID(), UUID.randomUUID(), action, List.of(), at));
        assertThat(result).isInstanceOf(RuntimeGovernanceResult.Governed.class);
        return (RuntimeGovernanceResult.Governed) result;
    }

    private String token(GovernedAction action) {
        var result = issuer.issue(organizationId, action.id());
        assertThat(result).isInstanceOf(ExecutionGrantIssuanceResult.Issued.class);
        return ((ExecutionGrantIssuanceResult.Issued) result).grant().compactToken();
    }

    private HttpResponse<String> execute(GovernedAction action, RuntimeGovernanceResult.Governed governed,
                                         String token, String payload) throws Exception {
        String body = "{\"organization_id\":\"" + organizationId + "\",\"agent_id\":\"" + agentId
                + "\",\"governed_action_id\":\"" + action.id() + "\",\"governance_decision_id\":\"" + governed.decision().id()
                + "\",\"tool_name\":\"payments\",\"operation_name\":\"refund\",\"payload\":" + payload + "}";
        return client.send(HttpRequest.newBuilder(executionUri).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
