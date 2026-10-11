package com.proofmesh.controlplane;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.proofmesh.controlplane.agent.AgentCredentialService;
import com.proofmesh.controlplane.agent.AuthenticatedAgent;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@SpringBootTest(properties="proofmesh.agent.requests-per-minute=2")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AgentRuntimeSecurityIntegrationTest.Probe.class})
@Transactional
class AgentRuntimeSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentCredentialService credentials;
    UUID organization=UUID.randomUUID(), agent=UUID.randomUUID();
    String secret;
    @BeforeEach void setup() {
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",organization,organization.toString(),"Runtime security");
        jdbc.update("INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES (?,?,?,'ACTIVE')",agent,organization,"Agent");
        secret=credentials.rotate(organization,agent,Duration.ofHours(1)).secret();
    }
    @Test void credentialDerivesIdentityAndOnlyAuthorizesRuntime() throws Exception {
        mvc.perform(post("/api/v1/runtime/security-probe").header("Authorization","Bearer "+secret).header("X-Organization-Id",UUID.randomUUID()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.organizationId").value(organization.toString())).andExpect(jsonPath("$.agentId").value(agent.toString()));
        mvc.perform(get("/api/v1/identity/me").header("Authorization","Bearer "+secret)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/agents/"+agent+"/credentials/rotate").header("Authorization","Bearer "+secret)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/runtime/security-probe").header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());
    }
    @Test void missingMalformedDuplicateUnknownRevokedAndHumanTokensAreRejected() throws Exception {
        mvc.perform(post("/api/v1/runtime/security-probe")).andExpect(status().isUnauthorized());
        for(String token:new String[]{"Basic x","Bearer junk","Bearer pm_agent_"+"a".repeat(43),"Bearer "+secret+",Bearer "+secret,"Bearer "+secret+" "})
            mvc.perform(post("/api/v1/runtime/security-probe").header("Authorization",token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/runtime/security-probe").header("Authorization","Bearer "+secret,"Bearer "+secret)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/runtime/security-probe").with(jwt())).andExpect(status().isUnauthorized());
        credentials.rotate(organization,agent,Duration.ofHours(1));
        mvc.perform(post("/api/v1/runtime/security-probe").header("Authorization","Bearer "+secret)).andExpect(status().isUnauthorized());
    }
    @Test void rateLimitAndAuditAreDurableAndContainNoSecret() throws Exception {
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/runtime/security-probe").header("Authorization","Bearer "+secret)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/runtime/security-probe").header("Authorization","Bearer "+secret))
            .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertThat(jdbc.queryForList("SELECT outcome FROM proofmesh.agent_runtime_audit WHERE agent_id=?",String.class,agent))
            .containsExactlyInAnyOrder("ADMITTED","ADMITTED","RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM proofmesh.agent_runtime_audit a WHERE agent_id=? LIMIT 1",String.class,agent)).doesNotContain(secret);
    }
    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentRequestsShareOneDatabaseLimit() throws Exception {
        var ready=new java.util.concurrent.CountDownLatch(8);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var results=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<8;i++) results.add(pool.submit(()->{
                ready.countDown();
                if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return mvc.perform(post("/api/v1/runtime/security-probe").header("Authorization","Bearer "+secret)).andReturn().getResponse().getStatus();
            }));
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            var statuses=new java.util.ArrayList<Integer>();
            for(var result:results) statuses.add(result.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(status->status==200).count()).isEqualTo(2);
            assertThat(statuses.stream().filter(status->status==429).count()).isEqualTo(6);
        } finally {start.countDown();}
    }

    @RestController static class Probe {
        @PostMapping("/api/v1/runtime/security-probe") AuthenticatedAgent probe(Authentication authentication) {
            return (AuthenticatedAgent)authentication.getPrincipal();
        }
    }
}
