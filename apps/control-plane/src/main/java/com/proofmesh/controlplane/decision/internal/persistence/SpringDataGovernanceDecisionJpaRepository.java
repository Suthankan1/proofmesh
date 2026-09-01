package com.proofmesh.controlplane.decision.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface SpringDataGovernanceDecisionJpaRepository
        extends JpaRepository<
                GovernanceDecisionJpaEntity,
                UUID
        > {

    Optional<GovernanceDecisionJpaEntity>
            findByOrganizationIdAndId(
                    UUID organizationId,
                    UUID id
            );

    Optional<GovernanceDecisionJpaEntity>
            findByOrganizationIdAndGovernedActionId(
                    UUID organizationId,
                    UUID governedActionId
            );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    INSERT INTO proofmesh.governance_decisions (
                        id,
                        organization_id,
                        governed_action_id,
                        policy_version_id,
                        matched_policy_rule_id,
                        outcome,
                        risk_score,
                        reason_codes,
                        decided_at
                    )
                    VALUES (
                        :id,
                        :organizationId,
                        :governedActionId,
                        :policyVersionId,
                        CAST(:matchedPolicyRuleId AS uuid),
                        :outcome,
                        :riskScore,
                        CAST(:reasonCodesJson AS jsonb),
                        :decidedAt
                    )
                    ON CONFLICT
                    ON CONSTRAINT uq_governance_decisions_action
                    DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("id")
            UUID id,

            @Param("organizationId")
            UUID organizationId,

            @Param("governedActionId")
            UUID governedActionId,

            @Param("policyVersionId")
            UUID policyVersionId,

            @Param("matchedPolicyRuleId")
            UUID matchedPolicyRuleId,

            @Param("outcome")
            String outcome,

            @Param("riskScore")
            int riskScore,

            @Param("reasonCodesJson")
            String reasonCodesJson,

            @Param("decidedAt")
            Instant decidedAt
    );
}