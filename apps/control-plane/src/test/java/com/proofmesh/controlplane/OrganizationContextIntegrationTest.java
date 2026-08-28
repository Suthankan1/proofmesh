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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrganizationContextIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");

    private static final UUID USER_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");

    private static final String SUBJECT =
            "keycloak-user-123";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "DELETE FROM proofmesh.organization_memberships"
        );

        jdbcTemplate.update(
                "DELETE FROM proofmesh.operator_users"
        );

        jdbcTemplate.update(
                "DELETE FROM proofmesh.organizations"
        );

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
                "proofmesh-demo",
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
                                .value("proofmesh-demo")
                )
                .andExpect(
                        jsonPath("$.oidcSubject")
                                .value(SUBJECT)
                );
    }

    @Test
    void rejectsAuthenticatedUserWithoutMembership() throws Exception {
        jdbcTemplate.update(
                "DELETE FROM proofmesh.organization_memberships"
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