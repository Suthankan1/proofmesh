package com.proofmesh.controlplane.runtimesubmission.internal;

import com.proofmesh.controlplane.agent.AuthenticatedAgent;
import com.proofmesh.controlplane.executiongrant.*;
import com.proofmesh.controlplane.governedaction.*;
import com.proofmesh.controlplane.runtimegovernance.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RuntimeSubmissionService {
    private final GovernedActionCreator creator;
    private final RuntimeGovernanceOrchestrator governance;
    private final RuntimeGrantIssuance issuance;
    private final Clock clock;
    public RuntimeSubmissionService(GovernedActionCreator creator, RuntimeGovernanceOrchestrator governance,
            RuntimeGrantIssuance issuance, Clock clock) {
        this.creator=creator; this.governance=governance; this.issuance=issuance; this.clock=clock;
    }
    public SubmissionResult submit(AuthenticatedAgent identity, String key, String tool, String operation, String payload) {
        var creation=creator.create(new CreateGovernedActionCommand(identity.organizationId(),identity.agentId(),
                new IdempotencyKey(key),new ToolName(tool),new OperationName(operation),payload));
        if(creation instanceof CreateGovernedActionResult.Rejected rejected)
            return new SubmissionResult(identity.organizationId(),identity.agentId(),null,null,"FAILED_CLOSED",null,null,null,null,rejected.reason().name());
        var action=switch(creation) {
            case CreateGovernedActionResult.Created created->created.governedAction();
            case CreateGovernedActionResult.Existing existing->existing.governedAction();
            case CreateGovernedActionResult.Rejected ignored->throw new IllegalStateException("Rejected action");
        };
        // Empty metadata preserves the existing CRITICAL/100 unknown-operation assessment.
        var result=governance.govern(new RuntimeGovernanceRequest(UUID.randomUUID(),UUID.randomUUID(),action,List.of(),clock.instant()));
        if(result instanceof RuntimeGovernanceResult.FailedClosed failed)
            return new SubmissionResult(identity.organizationId(),identity.agentId(),action.id(),null,"FAILED_CLOSED",null,action.requestPayload().hash().value(),null,null,failed.reason().name());
        var governed=(RuntimeGovernanceResult.Governed)result;
        var authority=issuance.issue(action);
        String token=authority.token();
        return new SubmissionResult(identity.organizationId(),identity.agentId(),action.id(),governed.decision().id(),
                governed.decision().outcome().name(),governed.approvalRequest().map(a->a.id()).orElse(null),
                action.requestPayload().hash().value(),governed.riskAssessment().riskScore().value(),token,
                authority.status());
    }
    public record SubmissionResult(UUID organizationId, UUID agentId, UUID governedActionId,
            UUID governanceDecisionId, String outcome, UUID approvalRequestId, String payloadHash,
            Integer riskScore, String executionGrant, String authorityStatus) {
        @Override public String toString() {return "SubmissionResult[outcome="+outcome+", executionGrant=[REDACTED]]";}
    }
}
