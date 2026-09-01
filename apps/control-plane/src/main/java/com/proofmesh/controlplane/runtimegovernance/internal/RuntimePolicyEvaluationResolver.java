package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

interface RuntimePolicyEvaluationResolver {

    PolicyEvaluationResult evaluate(
            RuntimeGovernanceContext context,
            RuntimeGovernanceRequest request,
            RiskAssessment riskAssessment
    );
}