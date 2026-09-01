package com.proofmesh.controlplane.runtimegovernance;

public enum RuntimeGovernanceFailureReason {

    AGENT_NOT_ACTIVE,

    NO_ACTIVE_POLICY_BINDING,

    POLICY_VERSION_UNAVAILABLE,

    POLICY_VERSION_NOT_PUBLISHED,

    RISK_ASSESSMENT_CONFLICT,

    GOVERNANCE_DECISION_CONFLICT
}