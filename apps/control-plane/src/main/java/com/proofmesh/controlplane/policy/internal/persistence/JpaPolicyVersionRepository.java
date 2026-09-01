package com.proofmesh.controlplane.policy.internal.persistence;

import com.proofmesh.controlplane.policy.CanonicalPolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionCanonicalizer;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.policy.PolicyVersionIntegrityException;
import com.proofmesh.controlplane.policy.PolicyVersionRepository;
import com.proofmesh.controlplane.policy.PolicyVersionState;
import com.proofmesh.controlplane.policy.PolicyVersionWriteConflictException;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPolicyVersionRepository
        implements PolicyVersionRepository {

    private final SpringDataPolicyVersionJpaRepository
            springDataRepository;

    private final PolicyVersionJpaMapper
            mapper;

    private final PolicyDefinitionCanonicalizer
            policyDefinitionCanonicalizer;

    JpaPolicyVersionRepository(
            SpringDataPolicyVersionJpaRepository
                    springDataRepository,
            PolicyVersionJpaMapper mapper,
            PolicyDefinitionCanonicalizer
                    policyDefinitionCanonicalizer
    ) {
        this.springDataRepository =
                Objects.requireNonNull(
                        springDataRepository,
                        "springDataRepository must not be null"
                );

        this.mapper =
                Objects.requireNonNull(
                        mapper,
                        "mapper must not be null"
                );

        this.policyDefinitionCanonicalizer =
                Objects.requireNonNull(
                        policyDefinitionCanonicalizer,
                        "policyDefinitionCanonicalizer must not be null"
                );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyVersion>
            findByOrganizationIdAndId(
                    UUID organizationId,
                    PolicyVersionId policyVersionId
            ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                policyVersionId,
                "policyVersionId must not be null"
        );

        return springDataRepository
                .findByOrganizationIdAndId(
                        organizationId,
                        policyVersionId.value()
                )
                .map(
                        mapper::toDomain
                );
    }

    @Override
    @Transactional
    public PolicyVersion insertDraft(
            PolicyVersion draft
    ) {
        requireDraft(
                draft
        );

        CanonicalPolicyDefinition canonical =
                policyDefinitionCanonicalizer
                        .canonicalize(
                                draft.definition()
                        );

        int affectedRows =
                springDataRepository.insertDraft(
                        draft.id().value(),
                        draft.policyId(),
                        draft.organizationId(),
                        draft.versionNumber()
                                .value(),
                        canonical.canonicalJson(),
                        draft.createdAt()
                );

        if (affectedRows != 1) {
            throw new PolicyVersionWriteConflictException(
                    "policy version draft could not be inserted because its identity or version number already exists"
            );
        }

        return draft;
    }

    @Override
    @Transactional
    public PolicyVersion updateDraftDefinition(
            PolicyVersion expectedDraft,
            PolicyDefinition replacementDefinition
    ) {
        requireDraft(
                expectedDraft
        );

        Objects.requireNonNull(
                replacementDefinition,
                "replacementDefinition must not be null"
        );

        CanonicalPolicyDefinition expectedCanonical =
                policyDefinitionCanonicalizer
                        .canonicalize(
                                expectedDraft.definition()
                        );

        CanonicalPolicyDefinition replacementCanonical =
                policyDefinitionCanonicalizer
                        .canonicalize(
                                replacementDefinition
                        );

        int affectedRows =
                springDataRepository
                        .updateDraftDefinitionIfUnchanged(
                                expectedDraft.id()
                                        .value(),
                                expectedDraft.policyId(),
                                expectedDraft.organizationId(),
                                expectedDraft.versionNumber()
                                        .value(),
                                expectedCanonical
                                        .canonicalJson(),
                                replacementCanonical
                                        .canonicalJson()
                        );

        if (affectedRows != 1) {
            throw new PolicyVersionWriteConflictException(
                    "policy version draft changed or is no longer editable"
            );
        }

        return new PolicyVersion(
                expectedDraft.id(),
                expectedDraft.policyId(),
                expectedDraft.organizationId(),
                expectedDraft.versionNumber(),
                PolicyVersionState.DRAFT,
                replacementDefinition,
                null,
                expectedDraft.createdAt(),
                null
        );
    }

    @Override
    @Transactional
    public PolicyVersion persistPublication(
            PolicyVersion publishedVersion
    ) {
        requirePublished(
                publishedVersion
        );

        CanonicalPolicyDefinition canonical =
                policyDefinitionCanonicalizer
                        .canonicalize(
                                publishedVersion.definition()
                        );

        if (!canonical.hash()
                .equals(
                        publishedVersion
                                .definitionHash()
                )) {
            throw new PolicyVersionIntegrityException(
                    "published policy version definition hash does not match its definition"
            );
        }

        int affectedRows =
                springDataRepository
                        .publishIfDraftMatches(
                                publishedVersion.id()
                                        .value(),
                                publishedVersion.policyId(),
                                publishedVersion.organizationId(),
                                publishedVersion.versionNumber()
                                        .value(),
                                canonical.canonicalJson(),
                                canonical.hash()
                                        .value(),
                                publishedVersion.publishedAt()
                        );

        if (affectedRows != 1) {
            throw new PolicyVersionWriteConflictException(
                    "policy version could not be published because the persisted draft changed or is no longer publishable"
            );
        }

        return publishedVersion;
    }

    private void requireDraft(
            PolicyVersion policyVersion
    ) {
        Objects.requireNonNull(
                policyVersion,
                "policyVersion must not be null"
        );

        if (policyVersion.state()
                != PolicyVersionState.DRAFT) {
            throw new IllegalArgumentException(
                    "policy version must be a draft"
            );
        }
    }

    private void requirePublished(
            PolicyVersion policyVersion
    ) {
        Objects.requireNonNull(
                policyVersion,
                "policyVersion must not be null"
        );

        if (policyVersion.state()
                != PolicyVersionState.PUBLISHED) {
            throw new IllegalArgumentException(
                    "policy version must be published"
            );
        }
    }
}