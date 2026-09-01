package com.proofmesh.controlplane.policy.internal;

import com.proofmesh.controlplane.policy.CanonicalPolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionCanonicalizer;
import com.proofmesh.controlplane.policy.PolicyPublisher;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionState;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
class DefaultPolicyPublisher
        implements PolicyPublisher {

    private final PolicyDefinitionCanonicalizer
            policyDefinitionCanonicalizer;

    DefaultPolicyPublisher(
            PolicyDefinitionCanonicalizer
                    policyDefinitionCanonicalizer
    ) {
        this.policyDefinitionCanonicalizer =
                Objects.requireNonNull(
                        policyDefinitionCanonicalizer,
                        "policyDefinitionCanonicalizer must not be null"
                );
    }

    @Override
    public PolicyVersion publish(
            PolicyVersion draft,
            Instant publishedAt
    ) {
        Objects.requireNonNull(
                draft,
                "draft must not be null"
        );

        Objects.requireNonNull(
                publishedAt,
                "publishedAt must not be null"
        );

        if (draft.isPublished()) {
            throw new IllegalStateException(
                    "policy version is already published"
            );
        }

        if (publishedAt.isBefore(
                draft.createdAt()
        )) {
            throw new IllegalArgumentException(
                    "publishedAt must not be before createdAt"
            );
        }

        CanonicalPolicyDefinition canonicalDefinition =
                policyDefinitionCanonicalizer
                        .canonicalize(
                                draft.definition()
                        );

        return new PolicyVersion(
                draft.id(),
                draft.policyId(),
                draft.organizationId(),
                draft.versionNumber(),
                PolicyVersionState.PUBLISHED,
                draft.definition(),
                canonicalDefinition.hash(),
                draft.createdAt(),
                publishedAt
        );
    }
}