package com.proofmesh.controlplane.policy.internal.persistence;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.AgentPolicyBindingIntegrityException;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.springframework.stereotype.Component;

@Component
class AgentPolicyBindingJpaMapper {

    AgentPolicyBinding toDomain(
            AgentPolicyBindingJpaEntity entity
    ) {
        if (entity == null) {
            throw new AgentPolicyBindingIntegrityException(
                    "persisted agent policy binding must not be null"
            );
        }

        try {
            return new AgentPolicyBinding(
                    entity.id(),
                    entity.organizationId(),
                    entity.agentId(),
                    new PolicyVersionId(
                            entity.policyVersionId()
                    ),
                    entity.activatedAt(),
                    entity.deactivatedAt(),
                    entity.createdAt()
            );
        } catch (RuntimeException exception) {
            throw new AgentPolicyBindingIntegrityException(
                    "persisted agent policy binding contains invalid domain state",
                    exception
            );
        }
    }
}