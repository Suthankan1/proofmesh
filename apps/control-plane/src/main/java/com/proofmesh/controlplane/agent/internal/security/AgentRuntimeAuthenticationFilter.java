package com.proofmesh.controlplane.agent.internal.security;

import com.proofmesh.controlplane.agent.AgentCredentialService;
import com.proofmesh.controlplane.agent.AgentRuntimeAdmission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class AgentRuntimeAuthenticationFilter extends OncePerRequestFilter {
    private final AgentCredentialService credentials;
    private final AgentRuntimeAdmission admission;
    AgentRuntimeAuthenticationFilter(AgentCredentialService credentials, AgentRuntimeAdmission admission) {
        this.credentials=credentials;
        this.admission=admission;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        // Never inherit a human or session identity on the agent-only transport.
        SecurityContextHolder.clearContext();
        response.setHeader("Cache-Control","no-store");
        var headers=Collections.list(request.getHeaders("Authorization"));
        if(headers.size()!=1 || !headers.getFirst().matches("(?i)Bearer pm_agent_[A-Za-z0-9_-]{43}")) {
            response.sendError(401); return;
        }
        try {
            var identity=credentials.authenticate(headers.getFirst().substring(7));
            if(identity.isEmpty()) {response.sendError(401); return;}
            if(!"POST".equals(request.getMethod())) {response.sendError(403); return;}
            if(!admission.admit(identity.get())) {
                response.setHeader("Retry-After","60"); response.sendError(429); return;
            }
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity.get(),null,
                    List.of(new SimpleGrantedAuthority("SCOPE_runtime:submit"))));
            SecurityContextHolder.setContext(context);
        } catch(DataAccessException unavailable) {
            response.sendError(503); return;
        }
        try {chain.doFilter(request,response);} finally {SecurityContextHolder.clearContext();}
    }
}
