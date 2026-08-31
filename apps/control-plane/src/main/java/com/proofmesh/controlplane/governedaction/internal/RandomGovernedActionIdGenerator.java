package com.proofmesh.controlplane.governedaction.internal;

import com.proofmesh.controlplane.governedaction.GovernedActionIdGenerator;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class RandomGovernedActionIdGenerator
        implements GovernedActionIdGenerator {

    @Override
    public UUID nextId() {
        return UUID.randomUUID();
    }
}