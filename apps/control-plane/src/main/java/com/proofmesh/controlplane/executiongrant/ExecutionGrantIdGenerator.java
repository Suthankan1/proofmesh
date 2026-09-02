package com.proofmesh.controlplane.executiongrant;

@FunctionalInterface
public interface ExecutionGrantIdGenerator {

    ExecutionGrantId nextGrantId();
}
