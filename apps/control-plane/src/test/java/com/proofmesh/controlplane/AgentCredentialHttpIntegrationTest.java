package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AgentCredentialHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.proofmesh.controlplane.agent.AgentCredentialService credentials;
    private final UUID organization = UUID.randomUUID(), agent = UUID.randomUUID(), user = UUID.randomUUID();
    private final String subject = UUID.randomUUID().toString();
    @BeforeEach void fixtures() {
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')", organization, organization.toString(), "HTTP test");
        jdbc.update("INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES (?,?,?,'ACTIVE')", agent, organization, "Agent");
        jdbc.update("INSERT INTO proofmesh.operator_users(id,oidc_subject,status) VALUES (?,?,'ACTIVE')", user, subject);
        jdbc.update("INSERT INTO proofmesh.organization_memberships(organization_id,user_id,status) VALUES (?,?,'ACTIVE')", organization, user);
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rotate(UUID target, String body, String role) {
        return post("/api/v1/agents/" + target + "/credentials/rotate").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    @Test void adminReceivesNonCacheableSecret() throws Exception {
        mvc.perform(rotate(agent, "{\"validitySeconds\":3600}", "PLATFORM_ADMIN").header("X-Organization-Id", UUID.randomUUID()))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.secret").value(org.hamcrest.Matchers.matchesPattern("pm_agent_[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.expiresAt").exists());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.agent_credentials WHERE agent_id=? AND revoked_at IS NULL", Integer.class, agent)).isEqualTo(1);
    }
    @Test void unauthenticatedAndViewerCannotIssue() throws Exception {
        mvc.perform(post("/api/v1/agents/" + agent + "/credentials/rotate").contentType(MediaType.APPLICATION_JSON).content("{\"validitySeconds\":3600}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(rotate(agent, "{\"validitySeconds\":3600}", "VIEWER")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.agent_credentials WHERE agent_id=?", Integer.class, agent)).isZero();
    }
    @Test void crossTenantAgentAndInvalidExpiryAreRejected() throws Exception {
        UUID other = UUID.randomUUID(), otherAgent = UUID.randomUUID();
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')", other, other.toString(), "Other");
        jdbc.update("INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES (?,?,?,'ACTIVE')", otherAgent, other, "Other agent");
        mvc.perform(rotate(otherAgent, "{\"validitySeconds\":3600}", "PLATFORM_ADMIN")).andExpect(status().isNotFound());
        for (String body : new String[]{"{}", "{\"validitySeconds\":0}", "{\"validitySeconds\":7776001}", "{\"validitySeconds\":-1}"})
            mvc.perform(rotate(agent, body, "PLATFORM_ADMIN")).andExpect(status().isBadRequest());
    }
    @Test void inactiveMembershipPreventsIssuance() throws Exception {
        jdbc.update("UPDATE proofmesh.organization_memberships SET status='DISABLED' WHERE organization_id=? AND user_id=?", organization, user);
        mvc.perform(rotate(agent, "{\"validitySeconds\":3600}", "PLATFORM_ADMIN")).andExpect(status().isForbidden());
    }
    @Test void adminCanRevokeWithoutReplacementAndAuditUsesHumanIdentity() throws Exception {
        var issued=credentials.rotate(organization,agent,java.time.Duration.ofHours(1));
        mvc.perform(post("/api/v1/agents/"+agent+"/credentials/revoke")
                .with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isNoContent());
        assertThat(credentials.authenticate(issued.secret())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT actor_id FROM proofmesh.agent_credential_admin_audit WHERE agent_id=?",String.class,agent)).isEqualTo(subject);
        assertThat(jdbc.queryForObject("SELECT operation FROM proofmesh.agent_credential_admin_audit WHERE agent_id=?",String.class,agent)).isEqualTo("REVOKED");
    }

    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void failedAdminAuditRollsBackCredentialRotation() throws Exception {
        var original=credentials.rotate(organization,agent,java.time.Duration.ofHours(1));
        String name="test_admin_audit_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION proofmesh."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.agent_id='"+agent+"'::uuid THEN RAISE EXCEPTION 'test admin audit unavailable'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON proofmesh.agent_credential_admin_audit FOR EACH ROW EXECUTE FUNCTION proofmesh."+name+"()");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->mvc.perform(rotate(agent,"{\"validitySeconds\":3600}","PLATFORM_ADMIN")))
                    .isInstanceOf(jakarta.servlet.ServletException.class);
            assertThat(credentials.authenticate(original.secret())).isPresent();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM proofmesh.agent_credentials WHERE agent_id=?",Integer.class,agent)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER "+name+" ON proofmesh.agent_credential_admin_audit");
            jdbc.execute("DROP FUNCTION proofmesh."+name+"()");
        }
    }

}
