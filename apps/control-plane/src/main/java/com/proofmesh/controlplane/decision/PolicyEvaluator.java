package com.proofmesh.controlplane.decision;

import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.policy.PolicyVersion;

public interface PolicyEvaluator {

    PolicyEvaluationResult evaluate(
            PolicyVersion policyVersion,
            GovernedAction governedAction,
            RiskScore riskScore
    );
}