package com.proofmesh.controlplane.risk.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataRiskAssessmentJpaRepository
        extends JpaRepository<
                RiskAssessmentJpaEntity,
                UUID
        > {

    Optional<RiskAssessmentJpaEntity>
            findByOrganizationIdAndId(
                    UUID organizationId,
                    UUID id
            );

    Optional<RiskAssessmentJpaEntity>
            findByOrganizationIdAndGovernedActionId(
                    UUID organizationId,
                    UUID governedActionId
            );
}