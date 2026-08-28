package com.proofmesh.controlplane.identity;

public interface OrganizationContextResolver {

        OrganizationContext resolve(String oidcSubject);

}
