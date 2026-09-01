package com.proofmesh.controlplane.risk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentRequest;
import com.proofmesh.controlplane.risk.RiskAssessor;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class DefaultRiskAssessorTest {

    private static final UUID ASSESSMENT_ID =
            UUID.fromString(
                    "81000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "82000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "83000000-0000-0000-0000-000000000001"
            );

    private static final Instant ASSESSED_AT =
            Instant.parse(
                    "2026-09-01T08:00:00Z"
            );

    private RiskAssessor assessor;

    @BeforeEach
    void setUp() {
        assessor =
                new DefaultRiskAssessor();
    }

    @Test
    void producesDeterministicAssessmentFromSignals() {
        RiskAssessment assessment =
                assessor.assess(
                        request(
                                List.of(
                                        signal(
                                                "FINANCIAL_IMPACT",
                                                RiskSeverity.HIGH,
                                                40
                                        ),
                                        signal(
                                                "BASELINE_TOOL_RISK",
                                                RiskSeverity.MEDIUM,
                                                25
                                        )
                                )
                        )
                );

        assertThat(
                assessment.id()
        ).isEqualTo(
                ASSESSMENT_ID
        );

        assertThat(
                assessment.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                assessment.governedActionId()
        ).isEqualTo(
                GOVERNED_ACTION_ID
        );

        assertThat(
                assessment.logicVersion().value()
        ).isEqualTo(
                "deterministic-v1"
        );

        assertThat(
                assessment.riskScore()
        ).isEqualTo(
                new RiskScore(
                        65
                )
        );

        assertThat(
                assessment.assessedAt()
        ).isEqualTo(
                ASSESSED_AT
        );
    }

    @Test
    void ordersSignalsDeterministicallyByCode() {
        RiskAssessment assessment =
                assessor.assess(
                        request(
                                List.of(
                                        signal(
                                                "SENSITIVE_DATA",
                                                RiskSeverity.HIGH,
                                                20
                                        ),
                                        signal(
                                                "BASELINE_TOOL_RISK",
                                                RiskSeverity.MEDIUM,
                                                25
                                        ),
                                        signal(
                                                "FINANCIAL_IMPACT",
                                                RiskSeverity.HIGH,
                                                40
                                        )
                                )
                        )
                );

        assertThat(
                assessment.signals()
                        .stream()
                        .map(
                                signal ->
                                        signal.code().value()
                        )
                        .toList()
        ).containsExactly(
                "BASELINE_TOOL_RISK",
                "FINANCIAL_IMPACT",
                "SENSITIVE_DATA"
        );
    }

    @Test
    void capsRiskScoreAtOneHundred() {
        RiskAssessment assessment =
                assessor.assess(
                        request(
                                List.of(
                                        signal(
                                                "FINANCIAL_IMPACT",
                                                RiskSeverity.HIGH,
                                                70
                                        ),
                                        signal(
                                                "SENSITIVE_DATA",
                                                RiskSeverity.HIGH,
                                                60
                                        )
                                )
                        )
                );

        assertThat(
                assessment.riskScore()
        ).isEqualTo(
                new RiskScore(
                        100
                )
        );
    }

    @Test
    void failsClosedWhenNoRiskMetadataIsAvailable() {
        RiskAssessment assessment =
                assessor.assess(
                        request(
                                List.of()
                        )
                );

        assertThat(
                assessment.riskScore()
        ).isEqualTo(
                new RiskScore(
                        100
                )
        );

        assertThat(
                assessment.signals()
        ).hasSize(1);

        RiskSignal signal =
                assessment.signals()
                        .getFirst();

        assertThat(
                signal.code().value()
        ).isEqualTo(
                "UNCLASSIFIED_OPERATION"
        );

        assertThat(
                signal.severity()
        ).isEqualTo(
                RiskSeverity.CRITICAL
        );

        assertThat(
                signal.weight()
        ).isEqualTo(
                100
        );
    }

    @Test
    void sameInputsProduceSameAssessment() {
        RiskAssessmentRequest request =
                request(
                        List.of(
                                signal(
                                        "FINANCIAL_IMPACT",
                                        RiskSeverity.HIGH,
                                        40
                                ),
                                signal(
                                        "BASELINE_TOOL_RISK",
                                        RiskSeverity.MEDIUM,
                                        25
                                )
                        )
                );

        RiskAssessment first =
                assessor.assess(
                        request
                );

        RiskAssessment second =
                assessor.assess(
                        request
                );

        assertThat(
                second
        ).isEqualTo(
                first
        );
    }

    @Test
    void assessmentRequestDefensivelyCopiesSignals() {
        List<RiskSignal> mutable =
                new ArrayList<>();

        mutable.add(
                signal(
                        "BASELINE_TOOL_RISK",
                        RiskSeverity.MEDIUM,
                        25
                )
        );

        RiskAssessmentRequest request =
                request(
                        mutable
                );

        mutable.clear();

        assertThat(
                request.signals()
        ).hasSize(1);
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(
                () -> assessor.assess(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "request"
                );
    }

    private RiskAssessmentRequest request(
            List<RiskSignal> signals
    ) {
        return new RiskAssessmentRequest(
                ASSESSMENT_ID,
                ORGANIZATION_ID,
                GOVERNED_ACTION_ID,
                signals,
                ASSESSED_AT
        );
    }

    private RiskSignal signal(
            String code,
            RiskSeverity severity,
            int weight
    ) {
        return new RiskSignal(
                new RiskSignalCode(
                        code
                ),
                severity,
                weight,
                "Deterministic test risk signal."
        );
    }
}