package com.proofmesh.controlplane.agent.internal.web;

import com.proofmesh.controlplane.agent.AgentCredentialService;
import com.proofmesh.controlplane.identity.CurrentOrganization;
import com.proofmesh.controlplane.identity.OrganizationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/agents")
class AgentCredentialController {
    private final AgentCredentialService credentials;

    AgentCredentialController(AgentCredentialService credentials) {
        this.credentials = credentials;
    }

    @PostMapping("/{agentId}/credentials/rotate")
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
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(new CredentialResponse(issued.id(), issued.secret(), issued.expiresAt()));
        } catch (IllegalArgumentException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active scoped agent unavailable");
        }
    }

    record RotationRequest(Long validitySeconds) { }
    record CredentialResponse(UUID id, String secret, Instant expiresAt) {
        @Override public String toString() { return "CredentialResponse[secret=[REDACTED]]"; }
    }
}
