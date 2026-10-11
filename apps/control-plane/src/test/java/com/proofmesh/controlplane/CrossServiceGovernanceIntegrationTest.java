package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** Opt-in local cross-language proof, including cryptographically verified human JWTs and opaque agents. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "proofmesh.crossservice", matches = "true")
class CrossServiceGovernanceIntegrationTest {
    private static Path keyDirectory;
    private static HttpServer oidc;
    private static com.nimbusds.jose.jwk.RSAKey humanKey;
    private static String humanIssuer;
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    private static final String PAYLOAD = "{\"amount\":5000,\"paymentId\":\"local-test\"}";
    private final UUID organizationId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean toolFailure = new java.util.concurrent.atomic.AtomicBoolean();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private HttpServer tool;
    private Process gateway;
    private URI executionUri;

    @Autowired JdbcTemplate jdbc;
    @Autowired com.proofmesh.controlplane.agent.AgentCredentialService credentials;
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
        humanKey=new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("local-human-test").generate();
        oidc=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        oidc.createContext("/jwks",exchange->{
            byte[] body=new com.nimbusds.jose.jwk.JWKSet(humanKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}exchange.close();
        });
        oidc.start(); humanIssuer="http://127.0.0.1:"+oidc.getAddress().getPort();
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",()->humanIssuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",()->humanIssuer+"/jwks");
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
            exchange.sendResponseHeaders(toolFailure.get() ? 503 : 200, reply.length);
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
        if(oidc!=null) oidc.stop(0);
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
        assertThat(jdbc.queryForObject("SELECT execution_status FROM public.execution_grant_claims WHERE governed_action_id=?",
                String.class, action.id())).isEqualTo("RESULT_RECEIVED");
        assertThat(jdbc.queryForObject("SELECT response_bytes FROM public.execution_grant_claims WHERE governed_action_id=?",
                Long.class, action.id())).isEqualTo(8L);
        assertThat(jdbc.queryForObject("SELECT response_hash FROM public.execution_grant_claims WHERE governed_action_id=?",
                String.class, action.id())).isEqualTo(java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest("accepted".getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> jdbc.update("UPDATE public.execution_grant_claims SET execution_status='STARTED' WHERE governed_action_id=?", action.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE public.execution_grant_claims SET agent_id=? WHERE governed_action_id=?", UUID.randomUUID(), action.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM public.execution_grant_claims WHERE governed_action_id=?", action.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
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

    @Test
    void unsuccessfulToolResponseRecordsUnknownAndStillSpendsGrant() throws Exception {
        var action = actionWithPolicy(PolicyEffect.ALLOW);
        var governed = govern(action, Instant.now());
        String token = token(action);
        toolFailure.set(true);
        assertThat(execute(action, governed, token, PAYLOAD).statusCode()).isEqualTo(502);
        assertThat(jdbc.queryForObject("SELECT execution_status FROM public.execution_grant_claims WHERE governed_action_id=?",
                String.class, action.id())).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT response_hash FROM public.execution_grant_claims WHERE governed_action_id=?",
                String.class, action.id())).isNull();
        assertThat(execute(action, governed, token, PAYLOAD).statusCode()).isEqualTo(409);
        assertThat(toolCalls).hasValue(1);
    }

    @Test
    void authenticatedAgentAndHumanApprovalFlowExecutesExactActionOnce() throws Exception {
        var action=actionWithPolicy(PolicyEffect.REQUIRE_APPROVAL);
        String subject=UUID.randomUUID().toString();UUID user=UUID.randomUUID();
        jdbc.update("INSERT INTO proofmesh.operator_users(id,oidc_subject,status) VALUES (?,?,'ACTIVE')",user,subject);
        jdbc.update("INSERT INTO proofmesh.organization_memberships(organization_id,user_id,status) VALUES (?,?,'ACTIVE')",organizationId,user);
        String human=humanToken(subject,List.of("PLATFORM_ADMIN","APPROVER"),"proofmesh-control-plane");
        var rotation=humanRequest("/api/v1/agents/"+agentId+"/credentials/rotate",human,"{\"validitySeconds\":3600}");
        assertThat(rotation.statusCode()).isEqualTo(201);
        String secret=json.readTree(rotation.body()).get("secret").asText();
        var pending=submitHttp(action,secret,PAYLOAD);
        assertThat(pending.statusCode()).isEqualTo(200);
        var pendingBody=json.readTree(pending.body());
        assertThat(pendingBody.get("outcome").asText()).isEqualTo("REQUIRE_APPROVAL");
        assertThat(pendingBody.get("executionGrant").isNull()).isTrue();
        String path="/api/v1/approvals/"+pendingBody.get("approvalRequestId").asText()+"/approve";
        assertThat(humanRequest(path,secret,"{\"rationale\":\"Agent spoof\"}").statusCode()).isEqualTo(401);
        assertThat(humanRequest(path,humanToken(subject,List.of("VIEWER"),"proofmesh-control-plane"),"{\"rationale\":\"Viewer spoof\"}").statusCode()).isEqualTo(403);
        assertThat(humanRequest(path,humanToken(subject,List.of("APPROVER"),"wrong-audience"),"{\"rationale\":\"Wrong audience\"}").statusCode()).isEqualTo(401);
        assertThat(humanRequest(path,human,"{\"rationale\":\"Reviewed exact local action\"}").statusCode()).isEqualTo(200);
        assertThat(toolCalls).hasValue(0);
        var retry=json.readTree(submitHttp(action,secret,PAYLOAD).body());
        assertThat(retry.get("governedActionId").asText()).isEqualTo(action.id().toString());
        assertThat(retry.get("governanceDecisionId").asText()).isEqualTo(pendingBody.get("governanceDecisionId").asText());
        assertThat(retry.get("outcome").asText()).isEqualTo("REQUIRE_APPROVAL");
        assertThat(executeSubmitted(retry,"{\"amount\":5001,\"paymentId\":\"local-test\"}").statusCode()).isEqualTo(403);
        assertThat(toolCalls).hasValue(0);
        assertThat(executeSubmitted(retry,PAYLOAD).statusCode()).isEqualTo(200);
        stopGateway();startGateway();
        assertThat(executeSubmitted(retry,PAYLOAD).statusCode()).isEqualTo(409);
        assertThat(toolCalls).hasValue(1);
    }

    @Test
    void authenticatedAllowAndCredentialRotationPreserveSingleUseAuthority() throws Exception {
        var action=actionWithPolicy(PolicyEffect.ALLOW);
        var first=credentials.rotate(organizationId,agentId,Duration.ofHours(1));
        var allowed=json.readTree(submitHttp(action,first.secret(),PAYLOAD).body());
        assertThat(allowed.get("outcome").asText()).isEqualTo("ALLOW");
        assertThat(executeSubmitted(allowed,PAYLOAD).statusCode()).isEqualTo(200);
        var second=credentials.rotate(organizationId,agentId,Duration.ofHours(1));
        assertThat(submitHttp(action,first.secret(),PAYLOAD).statusCode()).isEqualTo(401);
        var retry=json.readTree(submitHttp(action,second.secret(),PAYLOAD).body());
        assertThat(retry.get("executionGrant").isNull()).isTrue();
        assertThat(retry.get("authorityStatus").asText()).isEqualTo("ALREADY_ISSUED");
        assertThat(executeSubmitted(allowed,PAYLOAD).statusCode()).isEqualTo(409);
        assertThat(toolCalls).hasValue(1);
    }

    @Test
    void concurrentHttpSubmissionsMintOnlyOneToken() throws Exception {
        var action=actionWithPolicy(PolicyEffect.ALLOW);
        String secret=credentials.rotate(organizationId,agentId,Duration.ofHours(1)).secret();
        var ready=new CountDownLatch(8);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{
                ready.countDown();assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();
                return submitHttp(action,secret,PAYLOAD);
            }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            var tokens=new ArrayList<tools.jackson.databind.JsonNode>();
            for(var future:futures) {
                var response=future.get(15,TimeUnit.SECONDS);assertThat(response.statusCode()).isEqualTo(200);
                var body=json.readTree(response.body());
                if(!body.get("executionGrant").isNull()) tokens.add(body);
                else assertThat(body.get("authorityStatus").asText()).isEqualTo("ALREADY_ISSUED");
            }
            assertThat(tokens).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.runtime_grant_issuances WHERE governed_action_id=?",Integer.class,action.id())).isEqualTo(1);
            assertThat(executeSubmitted(tokens.getFirst(),PAYLOAD).statusCode()).isEqualTo(200);
            assertThat(toolCalls).hasValue(1);
        } finally {start.countDown();}
    }
    @Test
    void issuancePersistenceFailureReturnsNoTokenAndDoesNotConsumeAction() throws Exception {
        var action=actionWithPolicy(PolicyEffect.ALLOW);
        String secret=credentials.rotate(organizationId,agentId,Duration.ofHours(1)).secret();
        String name="test_reject_issuance_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION proofmesh."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.governed_action_id='"+action.id()+"'::uuid THEN RAISE EXCEPTION 'test issuance unavailable'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON proofmesh.runtime_grant_issuances FOR EACH ROW EXECUTE FUNCTION proofmesh."+name+"()");
        try {
            var failed=submitHttp(action,secret,PAYLOAD);
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(json.readTree(failed.body()).has("executionGrant")).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.runtime_grant_issuances WHERE governed_action_id=?",Integer.class,action.id())).isZero();
            assertThat(toolCalls).hasValue(0);
        } finally {
            jdbc.execute("DROP TRIGGER "+name+" ON proofmesh.runtime_grant_issuances");
            jdbc.execute("DROP FUNCTION proofmesh."+name+"()");
        }
        var retry=json.readTree(submitHttp(action,secret,PAYLOAD).body());
        assertThat(executeSubmitted(retry,PAYLOAD).statusCode()).isEqualTo(200);
        assertThat(toolCalls).hasValue(1);
    }

    @Test
    void evidenceExportIsTenantScopedAndContainsNoCredentialsOrPayloadBodies() throws Exception {
        var action=actionWithPolicy(PolicyEffect.ALLOW);
        String secret=credentials.rotate(organizationId,agentId,Duration.ofHours(1)).secret();
        var submitted=json.readTree(submitHttp(action,secret,PAYLOAD).body());
        assertThat(executeSubmitted(submitted,PAYLOAD).statusCode()).isEqualTo(200);
        UUID user=UUID.randomUUID();String subject=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO proofmesh.operator_users(id,oidc_subject,status) VALUES (?,?,'ACTIVE')",user,subject);
        jdbc.update("INSERT INTO proofmesh.organization_memberships(organization_id,user_id,status) VALUES (?,?,'ACTIVE')",organizationId,user);
        String path="/api/v1/evidence/actions/"+action.id();
        var exported=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+springPort+path))
                .header("Authorization","Bearer "+humanToken(subject,List.of("SECURITY_OPERATOR"),"proofmesh-control-plane")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(exported.statusCode()).isEqualTo(200);
        var evidence=json.readTree(exported.body());
        assertThat(evidence.get("organizationId").asText()).isEqualTo(organizationId.toString());
        assertThat(evidence.get("action").get("id").asText()).isEqualTo(action.id().toString());
        assertThat(evidence.get("executions").get(0).get("execution_status").asText()).isEqualTo("RESULT_RECEIVED");
        assertThat(exported.body()).doesNotContain(secret,submitted.get("executionGrant").asText(),PAYLOAD,"accepted");
        Path archiveRoot=Files.createTempDirectory("proofmesh-local-archive-test-");
        try {
            var builder=new ProcessBuilder("python3","../../scripts/archive-local-evidence.py","--base-url","http://127.0.0.1:"+springPort,
                    "--action",action.id().toString(),"--output",archiveRoot.toString()).redirectErrorStream(true);
            builder.environment().put("PROOFMESH_HUMAN_TOKEN",humanToken(subject,List.of("SECURITY_OPERATOR"),"proofmesh-control-plane"));
            var exporter=builder.start();assertThat(exporter.waitFor(15,TimeUnit.SECONDS)).isTrue();
            String output=new String(exporter.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
            assertThat(exporter.exitValue()).isZero();
            var verify=new ProcessBuilder("python3","../../scripts/archive-local-evidence.py","--verify",output).redirectErrorStream(true).start();
            assertThat(verify.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(verify.exitValue()).isZero();
            assertThat(Files.readString(Path.of(output).resolve("evidence.json"))).doesNotContain(secret,submitted.get("executionGrant").asText());
        } finally {
            try(var paths=Files.walk(archiveRoot)){for(var archivedPath:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(archivedPath);}
        }

        var viewer=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+springPort+path))
                .header("Authorization","Bearer "+humanToken(subject,List.of("VIEWER"),"proofmesh-control-plane")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(viewer.statusCode()).isEqualTo(403);
        UUID other=UUID.randomUUID(),otherUser=UUID.randomUUID();String otherSubject=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",other,other.toString(),"Other evidence tenant");
        jdbc.update("INSERT INTO proofmesh.operator_users(id,oidc_subject,status) VALUES (?,?,'ACTIVE')",otherUser,otherSubject);
        jdbc.update("INSERT INTO proofmesh.organization_memberships(organization_id,user_id,status) VALUES (?,?,'ACTIVE')",other,otherUser);
        var isolated=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+springPort+path))
                .header("Authorization","Bearer "+humanToken(otherSubject,List.of("SECURITY_OPERATOR"),"proofmesh-control-plane")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(isolated.statusCode()).isEqualTo(404);
    }

    private String humanToken(String subject,List<String> roles,String audience) throws Exception {
        var jwt=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID(humanKey.getKeyID()).build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(humanIssuer).subject(subject).audience(audience)
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .claim("realm_access",Map.of("roles",roles)).build());
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(humanKey));return jwt.serialize();
    }
    private HttpResponse<String> humanRequest(String path,String token,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+springPort+path)).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> submitHttp(GovernedAction action,String secret,String payload) throws Exception {
        return humanRequest("/api/v1/runtime/actions",secret,json.writeValueAsString(Map.of("idempotencyKey",action.idempotencyKey().value(),
                "toolName",action.toolName().value(),"operationName",action.operationName().value(),"requestPayloadJson",payload)));
    }
    private HttpResponse<String> executeSubmitted(tools.jackson.databind.JsonNode result,String payload) throws Exception {
        String body=json.writeValueAsString(Map.of("organization_id",result.get("organizationId").asText(),"agent_id",result.get("agentId").asText(),
                "governed_action_id",result.get("governedActionId").asText(),"governance_decision_id",result.get("governanceDecisionId").asText(),
                "tool_name","payments","operation_name","refund","payload",json.readTree(payload)));
        return client.send(HttpRequest.newBuilder(executionUri).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+result.get("executionGrant").asText()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
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
