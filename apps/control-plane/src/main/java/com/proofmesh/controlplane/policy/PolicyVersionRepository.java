package com.proofmesh.controlplane.policy;

import java.util.Optional;
import java.util.UUID;

public interface PolicyVersionRepository {

    Optional<PolicyVersion> findByOrganizationIdAndId(
            UUID organizationId,
            PolicyVersionId policyVersionId
    );

    PolicyVersion insertDraft(
            PolicyVersion draft
    );

    PolicyVersion updateDraftDefinition(
            PolicyVersion expectedDraft,
            PolicyDefinition replacementDefinition
    );

    PolicyVersion persistPublication(
            PolicyVersion publishedVersion
    );
}