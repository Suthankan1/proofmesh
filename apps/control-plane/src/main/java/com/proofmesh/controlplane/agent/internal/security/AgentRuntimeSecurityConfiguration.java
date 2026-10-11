package com.proofmesh.controlplane.agent.internal.security;

import com.proofmesh.controlplane.agent.AgentCredentialService;
import com.proofmesh.controlplane.agent.AgentRuntimeAdmission;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
class AgentRuntimeSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain agentRuntimeSecurity(HttpSecurity http, AgentCredentialService credentials,
            AgentRuntimeAdmission admission) throws Exception {
        return http.securityMatcher("/api/v1/runtime/**")
                .csrf(csrf->csrf.disable())
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize->authorize.anyRequest().hasAuthority("SCOPE_runtime:submit"))
                .addFilterBefore(new AgentRuntimeAuthenticationFilter(credentials,admission), AnonymousAuthenticationFilter.class)
                .build();
    }
}
