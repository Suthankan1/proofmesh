package com.proofmesh.controlplane.agent.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.proofmesh.controlplane.agent.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class AgentRuntimeAuthenticationFilterTest {
    @Test void databaseFailuresNeverInvokeNextHandler() throws Exception {
        for(boolean failAuthentication : new boolean[]{true,false}) {
            var credentials=mock(AgentCredentialService.class);
            var admission=mock(AgentRuntimeAdmission.class);
            String secret="pm_agent_"+"a".repeat(43);
            var identity=new AuthenticatedAgent(UUID.randomUUID(),UUID.randomUUID());
            if(failAuthentication) when(credentials.authenticate(secret)).thenThrow(new DataAccessResourceFailureException("unavailable"));
            else {
                when(credentials.authenticate(secret)).thenReturn(Optional.of(identity));
                when(admission.admit(identity)).thenThrow(new DataAccessResourceFailureException("unavailable"));
            }
            var request=new MockHttpServletRequest("POST","/api/v1/runtime/actions");
            request.addHeader("Authorization","Bearer "+secret);
            var response=new MockHttpServletResponse();
            var next=mock(jakarta.servlet.FilterChain.class);
            new AgentRuntimeAuthenticationFilter(credentials,admission).doFilter(request,response,next);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            verifyNoInteractions(next);
        }
    }
}
