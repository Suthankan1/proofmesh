package com.proofmesh.controlplane.risk.internal;

import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentConflictException;
import com.proofmesh.controlplane.risk.RiskAssessmentInsertResult;
import com.proofmesh.controlplane.risk.RiskAssessmentRecorder;
import com.proofmesh.controlplane.risk.RiskAssessmentRepository;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
class DefaultRiskAssessmentRecorder
        implements RiskAssessmentRecorder {

    private final RiskAssessmentRepository repository;

    DefaultRiskAssessmentRecorder(
            RiskAssessmentRepository repository
    ) {
        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository must not be null"
                );
    }

    @Override
    public RiskAssessment record(
            RiskAssessment proposedAssessment
    ) {
        Objects.requireNonNull(
                proposedAssessment,
                "proposedAssessment must not be null"
        );

        RiskAssessmentInsertResult result =
                repository.insertIfAbsent(
                        proposedAssessment
                );

        return switch (result) {
            case RiskAssessmentInsertResult.Inserted inserted ->
                    inserted.assessment();

            case RiskAssessmentInsertResult.Existing existing ->
                    resolveExisting(
                            proposedAssessment,
                            existing.assessment()
                    );
        };
    }

    private RiskAssessment resolveExisting(
            RiskAssessment proposedAssessment,
            RiskAssessment authoritativeAssessment
    ) {
        if (authoritativeAssessment
                .hasSameAssessmentSemanticsAs(
                        proposedAssessment
                )) {
            return authoritativeAssessment;
        }

        throw new RiskAssessmentConflictException(
                "authoritative risk assessment conflicts with proposed assessment"
        );
    }
}