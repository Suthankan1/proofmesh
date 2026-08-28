package com.proofmesh.controlplane.identity.internal;

import com.proofmesh.controlplane.identity.OrganizationContext;
import com.proofmesh.controlplane.identity.OrganizationContextResolver;
import com.proofmesh.controlplane.identity.internal.persistence.IdentityMembershipLookup;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class DefaultOrganizationContextResolver
        implements OrganizationContextResolver {

    private final IdentityMembershipLookup membershipLookup;

    DefaultOrganizationContextResolver(
            IdentityMembershipLookup membershipLookup
    ) {
        this.membershipLookup = membershipLookup;
    }

    @Override
    public OrganizationContext resolve(String oidcSubject) {

        if (!StringUtils.hasText(oidcSubject)) {
            throw new AccessDeniedException(
                    "Organization access is unavailable"
            );
        }

        List<OrganizationContext> contexts =
                membershipLookup.findActiveContextsBySubject(oidcSubject);

        if (contexts.size() != 1) {
            throw new AccessDeniedException(
                    "Organization access is unavailable"
            );
        }

        return contexts.getFirst();
    }
}