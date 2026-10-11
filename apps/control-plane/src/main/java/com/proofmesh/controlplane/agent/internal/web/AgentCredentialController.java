package com.proofmesh.controlplane.agent.internal.web;

import com.proofmesh.controlplane.agent.AgentCredentialService;
import com.proofmesh.controlplane.identity.CurrentOrganization;
import com.proofmesh.controlplane.identity.OrganizationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/agents")
class AgentCredentialController {
    private final AgentCredentialService credentials;
    private final JdbcTemplate jdbc;

    AgentCredentialController(AgentCredentialService credentials, JdbcTemplate jdbc) {
        this.credentials = credentials;
        this.jdbc = jdbc;
    }

    @PostMapping("/{agentId}/credentials/rotate")
    @Transactional
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ResponseEntity<CredentialResponse> rotate(
            @CurrentOrganization OrganizationContext organization,
            @PathVariable UUID agentId,
            @RequestBody RotationRequest request) {
        if (request.validitySeconds() == null || request.validitySeconds() <= 0
                || request.validitySeconds() > Duration.ofDays(90).toSeconds()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Validity must be positive and at most 90 days");
        }
        try {
            var issued = credentials.rotate(organization.organizationId(), agentId,
                    Duration.ofSeconds(request.validitySeconds()));
            audit(organization,agentId,"ROTATED");
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(new CredentialResponse(issued.id(), issued.secret(), issued.expiresAt()));
        } catch (IllegalArgumentException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active scoped agent unavailable");
        }
    }

    @PostMapping("/{agentId}/credentials/revoke")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    ResponseEntity<Void> revoke(@CurrentOrganization OrganizationContext organization, @PathVariable UUID agentId) {
        if(!credentials.revoke(organization.organizationId(),agentId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Scoped agent unavailable");
        audit(organization,agentId,"REVOKED");
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private void audit(OrganizationContext organization, UUID agentId, String operation) {
        jdbc.update("INSERT INTO proofmesh.agent_credential_admin_audit(id,organization_id,agent_id,actor_id,operation) VALUES (?,?,?,?,?)",
                UUID.randomUUID(),organization.organizationId(),agentId,organization.oidcSubject(),operation);
    }

    record RotationRequest(Long validitySeconds) { }
    record CredentialResponse(UUID id, String secret, Instant expiresAt) {
        @Override public String toString() { return "CredentialResponse[secret=[REDACTED]]"; }
    }
}
