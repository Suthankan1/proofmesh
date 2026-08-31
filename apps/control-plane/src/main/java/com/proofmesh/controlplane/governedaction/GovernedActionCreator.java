package com.proofmesh.controlplane.governedaction;

public interface GovernedActionCreator {

    CreateGovernedActionResult create(
            CreateGovernedActionCommand command
    );
}