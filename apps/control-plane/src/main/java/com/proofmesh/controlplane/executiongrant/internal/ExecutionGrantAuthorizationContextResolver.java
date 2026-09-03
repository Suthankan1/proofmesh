package com.proofmesh.controlplane.executiongrant.internal;

import com.proofmesh.controlplane.executiongrant.ExecutionGrantAuthorizationContext;

import java.util.Optional;
import java.util.UUID;

interface ExecutionGrantAuthorizationContextResolver {

    Optional<ExecutionGrantAuthorizationContext> resolve(
            UUID organizationId,
            UUID governedActionId
    );
}
