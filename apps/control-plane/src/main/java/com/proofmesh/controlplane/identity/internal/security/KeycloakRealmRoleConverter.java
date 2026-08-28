package com.proofmesh.controlplane.identity.internal.security;

import com.proofmesh.controlplane.identity.ProofMeshRole;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

final class KeycloakRealmRoleConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess =
                jwt.getClaimAsMap("realm_access");

        if (realmAccess == null) {
            return List.of();
        }

        Object rolesClaim = realmAccess.get("roles");

        if (!(rolesClaim instanceof Collection<?> roles)) {
            return List.of();
        }

        List<GrantedAuthority> authorities = new ArrayList<>();

        for (Object value : roles) {
            if (!(value instanceof String roleName)) {
                continue;
            }

            ProofMeshRole.fromTokenRole(roleName)
                    .map(ProofMeshRole::authority)
                    .map(SimpleGrantedAuthority::new)
                    .ifPresent(authorities::add);
        }

        return List.copyOf(authorities);
    }
}