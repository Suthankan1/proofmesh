package com.proofmesh.controlplane.executiongrant;

import java.util.UUID;

public interface ExecutionGrantIssuer {

    ExecutionGrantIssuanceResult issue(
            UUID organizationId,
            UUID governedActionId
    );
}
