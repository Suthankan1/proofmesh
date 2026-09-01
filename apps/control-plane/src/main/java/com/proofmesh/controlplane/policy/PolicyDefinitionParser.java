package com.proofmesh.controlplane.policy;

public interface PolicyDefinitionParser {

    PolicyDefinition parse(
            String json
    );
}