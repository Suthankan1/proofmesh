package com.proofmesh.controlplane.risk.internal.persistence;

import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentInsertResult;
import com.proofmesh.controlplane.risk.RiskAssessmentIntegrityException;
import com.proofmesh.controlplane.risk.RiskAssessmentRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaRiskAssessmentRepository
        implements RiskAssessmentRepository {

    private final SpringDataRiskAssessmentJpaRepository
            springDataRepository;

    private final RiskAssessmentJpaMapper mapper;

    private final JdbcTemplate jdbcTemplate;

    JpaRiskAssessmentRepository(
            SpringDataRiskAssessmentJpaRepository springDataRepository,
            RiskAssessmentJpaMapper mapper,
            JdbcTemplate jdbcTemplate
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

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate must not be null"
                );
    }

    @Override
    public Optional<RiskAssessment> findByOrganizationIdAndId(
            UUID organizationId,
            UUID assessmentId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                assessmentId,
                "assessmentId must not be null"
        );

        return springDataRepository
                .findByOrganizationIdAndId(
                        organizationId,
                        assessmentId
                )
                .map(
                        mapper::toDomain
                );
    }

    @Override
    public Optional<RiskAssessment>
            findByOrganizationIdAndGovernedActionId(
                    UUID organizationId,
                    UUID governedActionId
            ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                governedActionId,
                "governedActionId must not be null"
        );

        return springDataRepository
                .findByOrganizationIdAndGovernedActionId(
                        organizationId,
                        governedActionId
                )
                .map(
                        mapper::toDomain
                );
    }

    @Override
    public RiskAssessmentInsertResult insertIfAbsent(
            RiskAssessment assessment
    ) {
        Objects.requireNonNull(
                assessment,
                "assessment must not be null"
        );

        int affectedRows =
                jdbcTemplate.update(
                        """
                        INSERT INTO proofmesh.risk_assessments (
                            id,
                            organization_id,
                            governed_action_id,
                            logic_version,
                            risk_score,
                            signals,
                            assessed_at
                        )
                        VALUES (
                            ?,
                            ?,
                            ?,
                            ?,
                            ?,
                            CAST(? AS jsonb),
                            ?
                        )
                        ON CONFLICT
                            ON CONSTRAINT uq_risk_assessments_action
                        DO NOTHING
                        """,
                        assessment.id(),
                        assessment.organizationId(),
                        assessment.governedActionId(),
                        assessment.logicVersion().value(),
                        (short) assessment.riskScore().value(),
                        mapper.toSignalsJson(
                                assessment
                        ),
                        OffsetDateTime.ofInstant(
                                assessment.assessedAt(),
                                ZoneOffset.UTC
                        )
                );

        if (affectedRows == 1) {
            return new RiskAssessmentInsertResult.Inserted(
                    assessment
            );
        }

        if (affectedRows != 0) {
            throw new RiskAssessmentIntegrityException(
                    "risk assessment insert affected an unexpected number of rows"
            );
        }

        RiskAssessment existing =
                findByOrganizationIdAndGovernedActionId(
                        assessment.organizationId(),
                        assessment.governedActionId()
                )
                        .orElseThrow(
                                () ->
                                        new RiskAssessmentIntegrityException(
                                                "risk assessment insert lost conflict but authoritative row cannot be loaded"
                                        )
                        );

        return new RiskAssessmentInsertResult.Existing(
                existing
        );
    }
}