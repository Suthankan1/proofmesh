package com.proofmesh.controlplane;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
        TestcontainersConfiguration.class,
        SecurityIntegrationTest.SecurityProbeController.class
})
class SecurityIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void apiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedViewerCanAccessReadEndpoint() throws Exception {
        mockMvc.perform(
                        get("/api/v1/identity/me")
                                .with(jwt().authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_VIEWER"
                                        )
                                ))
                )
                .andExpect(status().isOk());
    }

    @Test
    void viewerCannotPerformPlatformAdminMutation() throws Exception {
        mockMvc.perform(
                        post("/api/test/admin-mutation")
                                .with(jwt().authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_VIEWER"
                                        )
                                ))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminCanPerformPlatformAdminMutation() throws Exception {
        mockMvc.perform(
                        post("/api/test/admin-mutation")
                                .with(jwt().authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_PLATFORM_ADMIN"
                                        )
                                ))
                )
                .andExpect(status().isNoContent());
    }

    @RestController
    static class SecurityProbeController {

        @PostMapping("/api/test/admin-mutation")
        @PreAuthorize("hasRole('PLATFORM_ADMIN')")
        ResponseEntity<Void> mutate() {
            return ResponseEntity.noContent().build();
        }
    }
}