package com.proofmesh.controlplane.risk;

import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentRepository {

    Optional<RiskAssessment> findByOrganizationIdAndId(
            UUID organizationId,
            UUID assessmentId
    );

    Optional<RiskAssessment>
            findByOrganizationIdAndGovernedActionId(
                    UUID organizationId,
                    UUID governedActionId
            );

    RiskAssessmentInsertResult insertIfAbsent(
            RiskAssessment assessment
    );
}