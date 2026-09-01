package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentResolution;
import com.proofmesh.controlplane.agent.AgentResolver;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.policy.AgentPolicyBindingResolution;
import com.proofmesh.controlplane.policy.AgentPolicyBindingResolver;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class DefaultRuntimeGovernancePrerequisiteResolver
        implements RuntimeGovernancePrerequisiteResolver {

    private final AgentResolver agentResolver;

    private final AgentPolicyBindingResolver
            policyBindingResolver;

    DefaultRuntimeGovernancePrerequisiteResolver(
            AgentResolver agentResolver,
            AgentPolicyBindingResolver policyBindingResolver
    ) {
        this.agentResolver =
                Objects.requireNonNull(
                        agentResolver,
                        "agentResolver must not be null"
                );

        this.policyBindingResolver =
                Objects.requireNonNull(
                        policyBindingResolver,
                        "policyBindingResolver must not be null"
                );
    }

    @Override
    public RuntimeGovernancePrerequisiteResolution resolve(
            RuntimeGovernanceRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        GovernedAction action =
                request.governedAction();

        AgentResolution agentResolution =
                agentResolver.resolve(
                        action.agentId(),
                        action.organizationId()
                );

        return switch (agentResolution) {
            case AgentResolution.Active active ->
                    resolvePolicyBinding(
                            active.agent(),
                            request
                    );

            case AgentResolution.Unknown ignored ->
                    new RuntimeGovernancePrerequisiteResolution.Failed(
                            RuntimeGovernanceFailureReason
                                    .AGENT_NOT_ACTIVE
                    );

            case AgentResolution.Disabled ignored ->
                    new RuntimeGovernancePrerequisiteResolution.Failed(
                            RuntimeGovernanceFailureReason
                                    .AGENT_NOT_ACTIVE
                    );
        };
    }

    private RuntimeGovernancePrerequisiteResolution
            resolvePolicyBinding(
                    Agent agent,
                    RuntimeGovernanceRequest request
            ) {

        AgentPolicyBindingResolution resolution =
                policyBindingResolver.resolve(
                        agent.organizationId(),
                        agent.id(),
                        request.evaluatedAt()
                );

        return switch (resolution) {
            case AgentPolicyBindingResolution.ActiveBinding active ->
                    new RuntimeGovernancePrerequisiteResolution.Ready(
                            agent,
                            active.binding()
                    );

            case AgentPolicyBindingResolution.NoBinding ignored ->
                    new RuntimeGovernancePrerequisiteResolution.Failed(
                            RuntimeGovernanceFailureReason
                                    .NO_ACTIVE_POLICY_BINDING
                    );
        };
    }
}