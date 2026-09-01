package com.proofmesh.controlplane.policy.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface SpringDataPolicyVersionJpaRepository
        extends JpaRepository<
                PolicyVersionJpaEntity,
                UUID
        > {

    Optional<PolicyVersionJpaEntity>
            findByOrganizationIdAndId(
                    UUID organizationId,
                    UUID id
            );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    INSERT INTO proofmesh.policy_versions (
                        id,
                        policy_id,
                        organization_id,
                        version_number,
                        state,
                        definition,
                        created_at
                    )
                    VALUES (
                        :id,
                        :policyId,
                        :organizationId,
                        :versionNumber,
                        'DRAFT',
                        CAST(:definitionJson AS jsonb),
                        :createdAt
                    )
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertDraft(
            @Param("id")
            UUID id,

            @Param("policyId")
            UUID policyId,

            @Param("organizationId")
            UUID organizationId,

            @Param("versionNumber")
            int versionNumber,

            @Param("definitionJson")
            String definitionJson,

            @Param("createdAt")
            Instant createdAt
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    UPDATE proofmesh.policy_versions
                    SET definition =
                        CAST(:replacementDefinitionJson AS jsonb)
                    WHERE id = :id
                      AND policy_id = :policyId
                      AND organization_id = :organizationId
                      AND version_number = :versionNumber
                      AND state = 'DRAFT'
                      AND definition_hash IS NULL
                      AND published_at IS NULL
                      AND definition =
                          CAST(:expectedDefinitionJson AS jsonb)
                    """,
            nativeQuery = true
    )
    int updateDraftDefinitionIfUnchanged(
            @Param("id")
            UUID id,

            @Param("policyId")
            UUID policyId,

            @Param("organizationId")
            UUID organizationId,

            @Param("versionNumber")
            int versionNumber,

            @Param("expectedDefinitionJson")
            String expectedDefinitionJson,

            @Param("replacementDefinitionJson")
            String replacementDefinitionJson
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    UPDATE proofmesh.policy_versions
                    SET state = 'PUBLISHED',
                        definition_hash = :definitionHash,
                        published_at = :publishedAt
                    WHERE id = :id
                      AND policy_id = :policyId
                      AND organization_id = :organizationId
                      AND version_number = :versionNumber
                      AND state = 'DRAFT'
                      AND definition_hash IS NULL
                      AND published_at IS NULL
                      AND definition =
                          CAST(:expectedDefinitionJson AS jsonb)
                    """,
            nativeQuery = true
    )
    int publishIfDraftMatches(
            @Param("id")
            UUID id,

            @Param("policyId")
            UUID policyId,

            @Param("organizationId")
            UUID organizationId,

            @Param("versionNumber")
            int versionNumber,

            @Param("expectedDefinitionJson")
            String expectedDefinitionJson,

            @Param("definitionHash")
            String definitionHash,

            @Param("publishedAt")
            Instant publishedAt
    );
}