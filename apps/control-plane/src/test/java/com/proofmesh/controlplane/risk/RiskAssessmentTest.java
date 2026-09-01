package com.proofmesh.controlplane.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.decision.RiskScore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class RiskAssessmentTest {

    private static final RiskSignalCode BASELINE_CODE =
            new RiskSignalCode(
                    "BASELINE_TOOL_RISK"
            );

    private static final Instant ASSESSED_AT =
            Instant.parse(
                    "2026-09-01T07:00:00Z"
            );

    @Test
    void createsImmutableAssessmentWithDeterministicProvenance() {
        RiskSignal signal =
                new RiskSignal(
                        BASELINE_CODE,
                        RiskSeverity.MEDIUM,
                        30,
                        "Tool operation carries baseline runtime risk."
                );

        RiskAssessment assessment =
                assessment(
                        List.of(
                                signal
                        )
                );

        assertThat(
                assessment.logicVersion()
                        .value()
        ).isEqualTo(
                "deterministic-v1"
        );

        assertThat(
                assessment.riskScore()
        ).isEqualTo(
                new RiskScore(
                        30
                )
        );

        assertThat(
                assessment.signals()
        ).containsExactly(
                signal
        );
    }

    @Test
    void defensivelyCopiesSignals() {
        RiskSignal signal =
                signal(
                        BASELINE_CODE
                );

        List<RiskSignal> mutable =
                new java.util.ArrayList<>(
                        List.of(
                                signal
                        )
                );

        RiskAssessment assessment =
                assessment(
                        mutable
                );

        mutable.clear();

        assertThat(
                assessment.signals()
        ).containsExactly(
                signal
        );
    }

    @Test
    void rejectsEmptySignals() {
        assertThatThrownBy(
                () -> assessment(
                        List.of()
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "at least one signal"
                );
    }

    @Test
    void rejectsDuplicateSignalCodes() {
        RiskSignal first =
                signal(
                        BASELINE_CODE
                );

        RiskSignal second =
                new RiskSignal(
                        BASELINE_CODE,
                        RiskSeverity.HIGH,
                        50,
                        "Second signal with duplicate code."
                );

        assertThatThrownBy(
                () -> assessment(
                        List.of(
                                first,
                                second
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "unique"
                );
    }

    @Test
    void rejectsInvalidSignalWeight() {
        assertThatThrownBy(
                () -> new RiskSignal(
                        BASELINE_CODE,
                        RiskSeverity.HIGH,
                        101,
                        "Invalid signal."
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "between 0 and 100"
                );
    }

    @Test
    void rejectsInvalidLogicVersion() {
        assertThatThrownBy(
                () -> new RiskLogicVersion(
                        "DETERMINISTIC V1"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    private RiskAssessment assessment(
            List<RiskSignal> signals
    ) {
        return new RiskAssessment(
                UUID.fromString(
                        "51000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "52000000-0000-0000-0000-000000000001"
                ),
                UUID.fromString(
                        "53000000-0000-0000-0000-000000000001"
                ),
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                new RiskScore(
                        30
                ),
                signals,
                ASSESSED_AT
        );
    }

    private RiskSignal signal(
            RiskSignalCode code
    ) {
        return new RiskSignal(
                code,
                RiskSeverity.MEDIUM,
                30,
                "Tool operation carries baseline runtime risk."
        );
    }

    @Test
    void sameAssessmentSemanticsIgnoreIdentityAndAssessmentTime() {
        RiskAssessment first =
                new RiskAssessment(
                        UUID.randomUUID(),
                        UUID.fromString(
                                "52000000-0000-0000-0000-000000000001"
                        ),
                        UUID.fromString(
                                "53000000-0000-0000-0000-000000000001"
                        ),
                        new RiskLogicVersion(
                                "deterministic-v1"
                        ),
                        new RiskScore(
                                30
                        ),
                        List.of(
                                signal(
                                        BASELINE_CODE
                                )
                        ),
                        ASSESSED_AT
                );

        RiskAssessment second =
                new RiskAssessment(
                        UUID.randomUUID(),
                        first.organizationId(),
                        first.governedActionId(),
                        first.logicVersion(),
                        first.riskScore(),
                        first.signals(),
                        ASSESSED_AT.plusSeconds(30)
                );

        assertThat(
                first.hasSameAssessmentSemanticsAs(
                        second
                )
        ).isTrue();
    }

    @Test
    void differentRiskScoreChangesAssessmentSemantics() {
        RiskAssessment first =
                assessment(
                        List.of(
                                signal(
                                        BASELINE_CODE
                                )
                        )
                );

        RiskAssessment second =
                new RiskAssessment(
                        UUID.randomUUID(),
                        first.organizationId(),
                        first.governedActionId(),
                        first.logicVersion(),
                        new RiskScore(
                                31
                        ),
                        first.signals(),
                        first.assessedAt()
                );

        assertThat(
                first.hasSameAssessmentSemanticsAs(
                        second
                )
        ).isFalse();
    }

    @Test
    void nullAssessmentNeverHasSameSemantics() {
        RiskAssessment assessment =
                assessment(
                        List.of(
                                signal(
                                        BASELINE_CODE
                                )
                        )
                );

        assertThat(
                assessment.hasSameAssessmentSemanticsAs(
                        null
                )
        ).isFalse();
    }
}