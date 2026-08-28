package com.proofmesh.controlplane.identity.internal.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class IdentityWebConfiguration implements WebMvcConfigurer {

    private final OrganizationContextArgumentResolver organizationResolver;

    IdentityWebConfiguration(
            OrganizationContextArgumentResolver organizationResolver
    ) {
        this.organizationResolver = organizationResolver;
    }

    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver> resolvers
    ) {
        resolvers.add(organizationResolver);
    }
}