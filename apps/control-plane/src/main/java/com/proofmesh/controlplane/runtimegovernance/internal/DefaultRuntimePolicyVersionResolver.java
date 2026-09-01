package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionRepository;
import com.proofmesh.controlplane.policy.PolicyVersionState;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Component
class DefaultRuntimePolicyVersionResolver
        implements RuntimePolicyVersionResolver {

    private final PolicyVersionRepository policyVersionRepository;

    DefaultRuntimePolicyVersionResolver(
            PolicyVersionRepository policyVersionRepository
    ) {
        this.policyVersionRepository =
                Objects.requireNonNull(
                        policyVersionRepository,
                        "policyVersionRepository must not be null"
                );
    }

    @Override
    public RuntimePolicyVersionResolution resolve(
            AgentPolicyBinding binding,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(
                binding,
                "binding must not be null"
        );

        Objects.requireNonNull(
                evaluatedAt,
                "evaluatedAt must not be null"
        );

        Optional<PolicyVersion> policyVersion =
                policyVersionRepository
                        .findByOrganizationIdAndId(
                                binding.organizationId(),
                                binding.policyVersionId()
                        );

        if (policyVersion.isEmpty()) {
            return new RuntimePolicyVersionResolution.Failed(
                    RuntimeGovernanceFailureReason
                            .POLICY_VERSION_UNAVAILABLE
            );
        }

        PolicyVersion resolved =
                policyVersion.orElseThrow();

        if (resolved.state()
                != PolicyVersionState.PUBLISHED) {
            return new RuntimePolicyVersionResolution.Failed(
                    RuntimeGovernanceFailureReason
                            .POLICY_VERSION_NOT_PUBLISHED
            );
        }

        if (resolved.publishedAt() == null) {
            return new RuntimePolicyVersionResolution.Failed(
                    RuntimeGovernanceFailureReason
                            .POLICY_VERSION_NOT_PUBLISHED
            );
        }

        if (resolved.publishedAt()
                .isAfter(
                        evaluatedAt
                )) {
            return new RuntimePolicyVersionResolution.Failed(
                    RuntimeGovernanceFailureReason
                            .POLICY_VERSION_NOT_PUBLISHED
            );
        }

        return new RuntimePolicyVersionResolution.Available(
                resolved
        );
    }
}