package com.proofmesh.controlplane.agent;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Application port; administrative transport must authorize rotation separately. */
public interface AgentCredentialService {
    IssuedAgentCredential rotate(UUID organizationId, UUID agentId, Duration validity);
    Optional<AuthenticatedAgent> authenticate(String opaqueCredential);
}
