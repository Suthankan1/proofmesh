package com.proofmesh.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.proofmesh.controlplane.agent.AgentCredentialService;
import com.proofmesh.controlplane.policy.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RuntimeSubmissionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentCredentialService credentials;
    @Autowired PolicyVersionRepository versions;
    @Autowired PolicyPublisher publisher;
    UUID organization=UUID.randomUUID(), agent=UUID.randomUUID();
    String secret;
    @BeforeEach void setup() {
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",organization,organization.toString(),"Submission");
        jdbc.update("INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES (?,?,?,'ACTIVE')",agent,organization,"Agent");
        secret=credentials.rotate(organization,agent,Duration.ofHours(1)).secret();
    }
    void policy(PolicyEffect effect) {
        Instant created=Instant.now().minusSeconds(10);
        UUID policy=UUID.randomUUID();
        jdbc.update("INSERT INTO proofmesh.policies(id,organization_id,name,created_at) VALUES (?,?,?,?)",policy,organization,"Runtime policy",OffsetDateTime.ofInstant(created,ZoneOffset.UTC));
        var rule=new PolicyRule(new PolicyRuleId(UUID.randomUUID()),new PolicyRulePriority(100),new PolicyTarget("payments","refund"),new PolicyRiskThreshold(0),effect,new PolicyReasonCode("HTTP_TEST"));
        var draft=new PolicyVersion(new PolicyVersionId(UUID.randomUUID()),policy,organization,new PolicyVersionNumber(1),PolicyVersionState.DRAFT,new PolicyDefinition(List.of(rule)),null,created,null);
        versions.insertDraft(draft);versions.persistPublication(publisher.publish(draft,created.plusSeconds(1)));
        jdbc.update("INSERT INTO proofmesh.agent_policy_bindings(id,organization_id,agent_id,policy_version_id,activated_at) VALUES (?,?,?,?,?)",UUID.randomUUID(),organization,agent,draft.id().value(),OffsetDateTime.ofInstant(created.plusSeconds(2),ZoneOffset.UTC));
    }
    String body(String payload) { return "{\"idempotencyKey\":\"retry-key\",\"toolName\":\"payments\",\"operationName\":\"refund\",\"requestPayloadJson\":\""+payload.replace("\"","\\\"")+"\"}"; }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(String body) {
        return post("/api/v1/runtime/actions").header("Authorization","Bearer "+secret).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    @Test void denyHasNoGrantAndUsesServerIdentityAndRisk() throws Exception {
        policy(PolicyEffect.DENY);
        mvc.perform(submit(body("{\"amount\":1}"))).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DENY"))
            .andExpect(jsonPath("$.organizationId").value(organization.toString())).andExpect(jsonPath("$.agentId").value(agent.toString()))
            .andExpect(jsonPath("$.riskScore").value(100)).andExpect(jsonPath("$.executionGrant").doesNotExist());
    }
    @Test void pendingExactRetryKeepsActionAndChangedPayloadConflicts() throws Exception {
        policy(PolicyEffect.REQUIRE_APPROVAL);
        String first=mvc.perform(submit(body("{\"amount\":1}"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("REQUIRE_APPROVAL")).andExpect(jsonPath("$.approvalRequestId").exists())
            .andExpect(jsonPath("$.executionGrant").doesNotExist()).andReturn().getResponse().getContentAsString();
        mvc.perform(submit(body("{ \"amount\" : 1 }"))).andExpect(status().isOk()).andExpect(content().json(first));
        mvc.perform(submit(body("{\"amount\":2}"))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.governed_actions WHERE agent_id=?",Integer.class,agent)).isEqualTo(1);
    }
    @Test void authoritativeFieldsAndMalformedPayloadAreRejected() throws Exception {
        mvc.perform(submit(body("{}").replaceFirst("\\{","{\"riskScore\":0,"))).andExpect(status().isBadRequest());
        mvc.perform(submit(body("not-json"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.governed_actions WHERE agent_id=?",Integer.class,agent)).isZero();
    }
    @Test void missingPolicyBindingFailsClosed() throws Exception {
        mvc.perform(submit(body("{}"))).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FAILED_CLOSED"))
            .andExpect(jsonPath("$.executionGrant").doesNotExist());
    }
    @Test void allowWithDisabledSigningNeverReturnsAuthority() throws Exception {
        policy(PolicyEffect.ALLOW);
        mvc.perform(submit(body("{}"))).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ALLOW"))
            .andExpect(jsonPath("$.authorityStatus").value("SIGNING_DISABLED")).andExpect(jsonPath("$.executionGrant").doesNotExist());
    }
    @Test void onlyScopedHumanApproverCanResolveAndReadExactApproval() throws Exception {
        policy(PolicyEffect.REQUIRE_APPROVAL);
        mvc.perform(submit(body("{\"amount\":1}"))).andExpect(status().isOk());
        UUID approval=jdbc.queryForObject("SELECT id FROM proofmesh.approval_requests WHERE agent_id=?",UUID.class,agent);
        String subject=UUID.randomUUID().toString();UUID user=UUID.randomUUID();
        jdbc.update("INSERT INTO proofmesh.operator_users(id,oidc_subject,status) VALUES (?,?,'ACTIVE')",user,subject);
        jdbc.update("INSERT INTO proofmesh.organization_memberships(organization_id,user_id,status) VALUES (?,?,'ACTIVE')",organization,user);
        mvc.perform(get("/api/v1/approvals/"+approval).with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_APPROVER"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requestPayloadJson").value("{\"amount\":1}"));
        mvc.perform(post("/api/v1/approvals/"+approval+"/approve").header("Authorization","Bearer "+secret).contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Reviewed\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/approvals/"+approval+"/approve").with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))).contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Reviewed\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/approvals/"+approval+"/approve").with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_APPROVER"))).contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Reviewed exact action\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(jdbc.queryForObject("SELECT actor_id FROM proofmesh.approval_requests WHERE id=?",String.class,approval)).isEqualTo(subject);

    }

    @Test void oversizedAndDuplicateEnvelopeFieldsAreRejected() throws Exception {
        mvc.perform(submit(body("{\"value\":\""+"a".repeat(131073)+"\"}"))).andExpect(status().isPayloadTooLarge());
        mvc.perform(submit(body("{}").replaceFirst("\\{","{\"toolName\":\"other\","))).andExpect(status().isBadRequest());
    }

    @Test void trailingJsonAndCrossTenantHumanAccessAreRejected() throws Exception {
        mvc.perform(submit(body("{}")+"{}")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.governed_actions WHERE agent_id=?",Integer.class,agent)).isZero();
        policy(PolicyEffect.REQUIRE_APPROVAL);mvc.perform(submit(body("{}"))).andExpect(status().isOk());
        UUID approval=jdbc.queryForObject("SELECT id FROM proofmesh.approval_requests WHERE agent_id=?",UUID.class,agent);
        UUID other=UUID.randomUUID(),user=UUID.randomUUID();String subject=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",other,other.toString(),"Other human tenant");
        jdbc.update("INSERT INTO proofmesh.operator_users(id,oidc_subject,status) VALUES (?,?,'ACTIVE')",user,subject);
        jdbc.update("INSERT INTO proofmesh.organization_memberships(organization_id,user_id,status) VALUES (?,?,'ACTIVE')",other,user);
        mvc.perform(get("/api/v1/approvals/"+approval).with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_APPROVER"))))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/approvals/"+approval+"/approve").with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_APPROVER"))).contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"Cross tenant\"}"))
            .andExpect(status().isNotFound());
    }

}
