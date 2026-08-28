package com.proofmesh.controlplane.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter =
            new KeycloakRealmRoleConverter();

    @Test
    void mapsOnlyKnownProofMeshRealmRoles() {

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("user-123")
                .claim(
                        "realm_access",
                        Map.of(
                                "roles",
                                List.of(
                                        "VIEWER",
                                        "APPROVER",
                                        "offline_access"
                                )
                        )
                )
                .build();

        List<String> authorities = converter
                .convert(jwt)
                .stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();

        assertThat(authorities)
                .containsExactly(
                        "ROLE_APPROVER",
                        "ROLE_VIEWER"
                );
    }
}