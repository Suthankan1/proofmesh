package com.proofmesh.controlplane.runtimegovernance.internal;

import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class DefaultRuntimeGovernanceContextResolver
        implements RuntimeGovernanceContextResolver {

    private final RuntimeGovernancePrerequisiteResolver
            prerequisiteResolver;

    private final RuntimePolicyVersionResolver
            policyVersionResolver;

    DefaultRuntimeGovernanceContextResolver(
            RuntimeGovernancePrerequisiteResolver prerequisiteResolver,
            RuntimePolicyVersionResolver policyVersionResolver
    ) {
        this.prerequisiteResolver =
                Objects.requireNonNull(
                        prerequisiteResolver,
                        "prerequisiteResolver must not be null"
                );

        this.policyVersionResolver =
                Objects.requireNonNull(
                        policyVersionResolver,
                        "policyVersionResolver must not be null"
                );
    }

    @Override
    public RuntimeGovernanceContextResolution resolve(
            RuntimeGovernanceRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        RuntimeGovernancePrerequisiteResolution
                prerequisiteResolution =
                prerequisiteResolver.resolve(
                        request
                );

        return switch (prerequisiteResolution) {
            case RuntimeGovernancePrerequisiteResolution.Failed failed ->
                    new RuntimeGovernanceContextResolution.Failed(
                            failed.reason()
                    );

            case RuntimeGovernancePrerequisiteResolution.Ready ready ->
                    resolvePolicyVersion(
                            ready,
                            request
                    );
        };
    }

    private RuntimeGovernanceContextResolution
            resolvePolicyVersion(
                    RuntimeGovernancePrerequisiteResolution.Ready ready,
                    RuntimeGovernanceRequest request
            ) {

        RuntimePolicyVersionResolution resolution =
                policyVersionResolver.resolve(
                        ready.policyBinding(),
                        request.evaluatedAt()
                );

        return switch (resolution) {
            case RuntimePolicyVersionResolution.Failed failed ->
                    new RuntimeGovernanceContextResolution.Failed(
                            failed.reason()
                    );

            case RuntimePolicyVersionResolution.Available available ->
                    readyContext(
                            ready,
                            available.policyVersion()
                    );
        };
    }

    private RuntimeGovernanceContextResolution readyContext(
            RuntimeGovernancePrerequisiteResolution.Ready ready,
            PolicyVersion policyVersion
    ) {
        RuntimeGovernanceContext context =
                new RuntimeGovernanceContext(
                        ready.agent(),
                        ready.policyBinding(),
                        policyVersion
                );

        return new RuntimeGovernanceContextResolution.Ready(
                context
        );
    }
}