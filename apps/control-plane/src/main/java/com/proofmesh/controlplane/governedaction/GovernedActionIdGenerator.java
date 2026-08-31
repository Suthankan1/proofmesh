package com.proofmesh.controlplane.governedaction;

import java.util.UUID;

public interface GovernedActionIdGenerator {

    UUID nextId();
}