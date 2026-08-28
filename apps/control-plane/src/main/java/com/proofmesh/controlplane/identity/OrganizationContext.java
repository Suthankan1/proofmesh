package com.proofmesh.controlplane.identity;

import java.util.Objects;
import java.util.UUID;

public record OrganizationContext (
    UUID userId,
    UUID organizationId,
    String organizationSlug,
    String oidcSubject
) {
    public OrganizationContext {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(organizationSlug);
        Objects.requireNonNull(oidcSubject);
    }
}
