package com.proofmesh.controlplane.identity.internal.web;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.proofmesh.controlplane.identity.CurrentOrganization;
import com.proofmesh.controlplane.identity.OrganizationContext;

@RestController
@RequestMapping("/api/v1/identity")
class IdentityController {

    @GetMapping("/me")
    IdentityResponse me(JwtAuthenticationToken authentication) {

        List<String> authorities = authentication
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();

        return new IdentityResponse(
                authentication.getToken().getSubject(),
                authorities
        );
    }

    @GetMapping("/context")
    OrganizationContext organizationContext(
        @CurrentOrganization OrganizationContext organizationContext
        ) {
        return organizationContext;
        }

    record IdentityResponse(
            String subject,
            List<String> authorities
    ) {
    }
}