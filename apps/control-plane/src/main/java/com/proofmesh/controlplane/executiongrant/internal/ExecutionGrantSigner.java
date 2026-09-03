package com.proofmesh.controlplane.executiongrant.internal;

import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaims;
import com.proofmesh.controlplane.executiongrant.SignedExecutionGrant;

interface ExecutionGrantSigner {

    SignedExecutionGrant sign(
            ExecutionGrantClaims claims,
            ExecutionGrantSigningKey signingKey
    );
}
