package com.proofmesh.controlplane;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class OrganizationContextIntegrationTest {

    private final UUID ORGANIZATION_ID = UUID.randomUUID();

    private final UUID USER_ID = UUID.randomUUID();

    private final String SUBJECT = UUID.randomUUID().toString();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.organizations (
                    id,
                    slug,
                    name,
                    status
                )
                VALUES (?, ?, ?, ?)
                """,
                ORGANIZATION_ID,
                ORGANIZATION_ID.toString(),
                "ProofMesh Demo",
                "ACTIVE"
        );

        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.operator_users (
                    id,
                    oidc_subject,
                    display_name,
                    email,
                    status
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                USER_ID,
                SUBJECT,
                "Demo Viewer",
                "viewer@proofmesh.local",
                "ACTIVE"
        );

        jdbcTemplate.update(
                """
                INSERT INTO proofmesh.organization_memberships (
                    organization_id,
                    user_id,
                    status
                )
                VALUES (?, ?, ?)
                """,
                ORGANIZATION_ID,
                USER_ID,
                "ACTIVE"
        );
    }

    @Test
    void resolvesOrganizationFromAuthenticatedSubject() throws Exception {
        mockMvc.perform(
                        get("/api/v1/identity/context")
                                .with(jwt()
                                        .jwt(jwt -> jwt.subject(SUBJECT))
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_VIEWER"
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.userId")
                                .value(USER_ID.toString())
                )
                .andExpect(
                        jsonPath("$.organizationId")
                                .value(ORGANIZATION_ID.toString())
                )
                .andExpect(
                        jsonPath("$.organizationSlug")
                                .value(ORGANIZATION_ID.toString())
                )
                .andExpect(
                        jsonPath("$.oidcSubject")
                                .value(SUBJECT)
                );
    }

    @Test
    void rejectsAuthenticatedUserWithoutMembership() throws Exception {
        jdbcTemplate.update(
                "DELETE FROM proofmesh.organization_memberships WHERE organization_id=? AND user_id=?", ORGANIZATION_ID, USER_ID
        );

        mockMvc.perform(
                        get("/api/v1/identity/context")
                                .with(jwt()
                                        .jwt(jwt -> jwt.subject(SUBJECT))
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_VIEWER"
                                                )
                                        )
                                )
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void clientHeaderCannotOverrideOrganizationContext() throws Exception {
        UUID spoofedOrganization =
                UUID.fromString(
                        "99999999-0000-0000-0000-000000000999"
                );

        mockMvc.perform(
                        get("/api/v1/identity/context")
                                .header(
                                        "X-Organization-Id",
                                        spoofedOrganization
                                )
                                .with(jwt()
                                        .jwt(jwt -> jwt.subject(SUBJECT))
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_VIEWER"
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.organizationId")
                                .value(ORGANIZATION_ID.toString())
                );
    }
}