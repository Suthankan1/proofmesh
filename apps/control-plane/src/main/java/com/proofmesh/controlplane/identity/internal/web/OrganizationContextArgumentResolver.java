package com.proofmesh.controlplane.identity.internal.web;

import com.proofmesh.controlplane.identity.CurrentOrganization;
import com.proofmesh.controlplane.identity.OrganizationContext;
import com.proofmesh.controlplane.identity.OrganizationContextResolver;

import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
class OrganizationContextArgumentResolver
        implements HandlerMethodArgumentResolver {

    private final OrganizationContextResolver contextResolver;

    OrganizationContextArgumentResolver(
            OrganizationContextResolver contextResolver
    ) {
        this.contextResolver = contextResolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentOrganization.class)
                && parameter.getParameterType()
                .equals(OrganizationContext.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AccessDeniedException(
                    "Organization access is unavailable"
            );
        }

        return contextResolver.resolve(
                jwt.getToken().getSubject()
        );
    }
}