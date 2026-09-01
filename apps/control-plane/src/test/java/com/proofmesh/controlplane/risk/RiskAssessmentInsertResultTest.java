package com.proofmesh.controlplane.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.decision.RiskScore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class RiskAssessmentInsertResultTest {

    @Test
    void insertedCarriesAuthoritativeAssessment() {
        RiskAssessment assessment =
                assessment();

        RiskAssessmentInsertResult.Inserted result =
                new RiskAssessmentInsertResult.Inserted(
                        assessment
                );

        assertThat(
                result.assessment()
        ).isEqualTo(
                assessment
        );
    }

    @Test
    void existingCarriesAuthoritativeAssessment() {
        RiskAssessment assessment =
                assessment();

        RiskAssessmentInsertResult.Existing result =
                new RiskAssessmentInsertResult.Existing(
                        assessment
                );

        assertThat(
                result.assessment()
        ).isEqualTo(
                assessment
        );
    }

    @Test
    void insertedRejectsNullAssessment() {
        assertThatThrownBy(
                () -> new RiskAssessmentInsertResult.Inserted(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "assessment"
                );
    }

    @Test
    void existingRejectsNullAssessment() {
        assertThatThrownBy(
                () -> new RiskAssessmentInsertResult.Existing(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "assessment"
                );
    }

    private RiskAssessment assessment() {
        return new RiskAssessment(
                UUID.fromString(
                        "61000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "62000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "63000000-0000-0000-0000-000000000001"
                ),
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                new RiskScore(
                        30
                ),
                List.of(
                        new RiskSignal(
                                new RiskSignalCode(
                                        "BASELINE_TOOL_RISK"
                                ),
                                RiskSeverity.MEDIUM,
                                30,
                                "Tool operation carries baseline runtime risk."
                        )
                ),
                Instant.parse(
                        "2026-09-01T07:00:00Z"
                )
        );
    }
}