package com.proofmesh.controlplane.risk;

import java.util.Objects;

public sealed interface RiskAssessmentInsertResult
        permits RiskAssessmentInsertResult.Inserted,
                RiskAssessmentInsertResult.Existing {

    record Inserted(
            RiskAssessment assessment
    ) implements RiskAssessmentInsertResult {

        public Inserted {
            Objects.requireNonNull(
                    assessment,
                    "assessment must not be null"
            );
        }
    }

    record Existing(
            RiskAssessment assessment
    ) implements RiskAssessmentInsertResult {

        public Existing {
            Objects.requireNonNull(
                    assessment,
                    "assessment must not be null"
            );
        }
    }
}