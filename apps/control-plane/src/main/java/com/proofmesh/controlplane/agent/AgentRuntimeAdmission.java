package com.proofmesh.controlplane.agent;

/** Shared durable per-agent runtime admission; audit success is mandatory. */
public interface AgentRuntimeAdmission {
    boolean admit(AuthenticatedAgent agent);
}
