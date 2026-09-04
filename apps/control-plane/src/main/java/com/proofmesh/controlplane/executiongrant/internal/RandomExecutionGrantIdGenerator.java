package com.proofmesh.controlplane.executiongrant.internal;

import com.proofmesh.controlplane.executiongrant.ExecutionGrantId;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIdGenerator;

import java.util.UUID;

final class RandomExecutionGrantIdGenerator implements ExecutionGrantIdGenerator {

    @Override
    public ExecutionGrantId nextGrantId() {
        return new ExecutionGrantId(UUID.randomUUID());
    }
}
