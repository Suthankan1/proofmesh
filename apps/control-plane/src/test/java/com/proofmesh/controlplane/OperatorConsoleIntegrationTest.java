package com.proofmesh.controlplane;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class) @Transactional
class OperatorConsoleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    UUID organization=UUID.randomUUID(), user=UUID.randomUUID();
    String subject=UUID.randomUUID().toString();
    @BeforeEach void seed() {
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",organization,organization.toString(),"Console tenant");
        jdbc.update("INSERT INTO proofmesh.operator_users(id,oidc_subject,status) VALUES (?,?,'ACTIVE')",user,subject);
        jdbc.update("INSERT INTO proofmesh.organization_memberships(organization_id,user_id,status) VALUES (?,?,'ACTIVE')",organization,user);
        jdbc.update("INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES (?,?,?,'ACTIVE')",UUID.randomUUID(),organization,"Visible agent");
        UUID other=UUID.randomUUID();
        jdbc.update("INSERT INTO proofmesh.organizations(id,slug,name,status) VALUES (?,?,?,'ACTIVE')",other,other.toString(),"Hidden tenant");
        jdbc.update("INSERT INTO proofmesh.agents(id,organization_id,name,status) VALUES (?,?,?,'ACTIVE')",UUID.randomUUID(),other,"Hidden agent");
    }
    @Test void readIsTenantScopedAndNonCacheable() throws Exception {
        for(String role:new String[]{"VIEWER","APPROVER","SECURITY_OPERATOR","PLATFORM_ADMIN"}) {
            mvc.perform(get("/api/v1/console").header("X-Organization-Id",UUID.randomUUID())
                .with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_"+role))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.organization.id").value(organization.toString()))
                .andExpect(jsonPath("$.agents.length()").value(1))
                .andExpect(jsonPath("$.agents[0].name").value("Visible agent"))
                .andExpect(jsonPath("$.actions.length()").value(0));
        }
    }
    @Test void unknownRoleAndDisabledMembershipFailClosed() throws Exception {
        mvc.perform(get("/api/v1/console").with(jwt().jwt(t->t.subject(subject)))) .andExpect(status().isForbidden());
        jdbc.update("UPDATE proofmesh.organization_memberships SET status='DISABLED' WHERE user_id=?",user);
        mvc.perform(get("/api/v1/console").with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
            .andExpect(status().isForbidden());
    }
    @Test void listsAreBoundedAndPayloadsExcluded() throws Exception {
        UUID agent=jdbc.queryForObject("SELECT id FROM proofmesh.agents WHERE organization_id=?",UUID.class,organization);
        for(int i=0;i<101;i++) jdbc.update("INSERT INTO proofmesh.governed_actions(id,organization_id,agent_id,idempotency_key,tool_name,operation_name,request_payload,request_payload_hash) VALUES (?,?,?,?,?,?,'{\"private\":\"secret payload\"}'::jsonb,?)",UUID.randomUUID(),organization,agent,"secret key "+i,"payments","refund","0".repeat(64));
        mvc.perform(get("/api/v1/console").with(jwt().jwt(t->t.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
          .andExpect(status().isOk()).andExpect(jsonPath("$.actions.length()").value(100))
          .andExpect(jsonPath("$.actions[0].outcome").doesNotExist())
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret payload"))))
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret key"))));
    }
    @Test void anonymousCannotRead() throws Exception {mvc.perform(get("/api/v1/console")).andExpect(status().isUnauthorized());}
}
