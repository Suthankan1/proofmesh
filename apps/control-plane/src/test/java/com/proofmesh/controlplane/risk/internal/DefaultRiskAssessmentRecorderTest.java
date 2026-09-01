package com.proofmesh.controlplane.risk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentConflictException;
import com.proofmesh.controlplane.risk.RiskAssessmentInsertResult;
import com.proofmesh.controlplane.risk.RiskAssessmentRecorder;
import com.proofmesh.controlplane.risk.RiskAssessmentRepository;
import com.proofmesh.controlplane.risk.RiskLogicVersion;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultRiskAssessmentRecorderTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "91000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "92000000-0000-0000-0000-000000000001"
            );

    private static final UUID PROPOSED_ID =
            UUID.fromString(
                    "93000000-0000-0000-0000-000000000001"
            );

    private static final UUID AUTHORITATIVE_ID =
            UUID.fromString(
                    "93000000-0000-0000-0000-000000000002"
            );

    private static final Instant PROPOSED_AT =
            Instant.parse(
                    "2026-09-01T08:00:00Z"
            );

    private static final Instant AUTHORITATIVE_AT =
            Instant.parse(
                    "2026-09-01T08:00:01Z"
            );

    private RiskAssessmentRepository repository;

    private RiskAssessmentRecorder recorder;

    @BeforeEach
    void setUp() {
        repository =
                mock(
                        RiskAssessmentRepository.class
                );

        recorder =
                new DefaultRiskAssessmentRecorder(
                        repository
                );
    }

    @Test
    void returnsInsertedAssessmentWhenProposedAssessmentWins() {
        RiskAssessment proposed =
                assessment(
                        PROPOSED_ID,
                        65,
                        PROPOSED_AT
                );

        when(
                repository.insertIfAbsent(
                        proposed
                )
        ).thenReturn(
                new RiskAssessmentInsertResult.Inserted(
                        proposed
                )
        );

        RiskAssessment result =
                recorder.record(
                        proposed
                );

        assertThat(
                result
        ).isEqualTo(
                proposed
        );

        verify(
                repository
        ).insertIfAbsent(
                proposed
        );
    }

    @Test
    void equivalentRetryConvergesOnAuthoritativeAssessment() {
        RiskAssessment proposed =
                assessment(
                        PROPOSED_ID,
                        65,
                        PROPOSED_AT
                );

        RiskAssessment authoritative =
                assessment(
                        AUTHORITATIVE_ID,
                        65,
                        AUTHORITATIVE_AT
                );

        when(
                repository.insertIfAbsent(
                        proposed
                )
        ).thenReturn(
                new RiskAssessmentInsertResult.Existing(
                        authoritative
                )
        );

        RiskAssessment result =
                recorder.record(
                        proposed
                );

        assertThat(
                result
        ).isEqualTo(
                authoritative
        );

        assertThat(
                result.id()
        ).isEqualTo(
                AUTHORITATIVE_ID
        );
    }

    @Test
    void contradictoryRiskScoreFailsClosed() {
        RiskAssessment proposed =
                assessment(
                        PROPOSED_ID,
                        65,
                        PROPOSED_AT
                );

        RiskAssessment authoritative =
                assessment(
                        AUTHORITATIVE_ID,
                        90,
                        AUTHORITATIVE_AT
                );

        when(
                repository.insertIfAbsent(
                        proposed
                )
        ).thenReturn(
                new RiskAssessmentInsertResult.Existing(
                        authoritative
                )
        );

        assertThatThrownBy(
                () -> recorder.record(
                        proposed
                )
        )
                .isInstanceOf(
                        RiskAssessmentConflictException.class
                )
                .hasMessageContaining(
                        "conflicts"
                );
    }

    @Test
    void contradictorySignalsFailClosedEvenWhenScoreMatches() {
        RiskAssessment proposed =
                assessment(
                        PROPOSED_ID,
                        65,
                        PROPOSED_AT
                );

        RiskAssessment authoritative =
                new RiskAssessment(
                        AUTHORITATIVE_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        new RiskLogicVersion(
                                "deterministic-v1"
                        ),
                        new RiskScore(
                                65
                        ),
                        List.of(
                                new RiskSignal(
                                        new RiskSignalCode(
                                                "DIFFERENT_RISK_SIGNAL"
                                        ),
                                        RiskSeverity.HIGH,
                                        65,
                                        "Different deterministic risk provenance."
                                )
                        ),
                        AUTHORITATIVE_AT
                );

        when(
                repository.insertIfAbsent(
                        proposed
                )
        ).thenReturn(
                new RiskAssessmentInsertResult.Existing(
                        authoritative
                )
        );

        assertThatThrownBy(
                () -> recorder.record(
                        proposed
                )
        )
                .isInstanceOf(
                        RiskAssessmentConflictException.class
                );
    }

    @Test
    void differentLogicVersionFailsClosed() {
        RiskAssessment proposed =
                assessment(
                        PROPOSED_ID,
                        65,
                        PROPOSED_AT
                );

        RiskAssessment authoritative =
                new RiskAssessment(
                        AUTHORITATIVE_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        new RiskLogicVersion(
                                "deterministic-v2"
                        ),
                        new RiskScore(
                                65
                        ),
                        signals(),
                        AUTHORITATIVE_AT
                );

        when(
                repository.insertIfAbsent(
                        proposed
                )
        ).thenReturn(
                new RiskAssessmentInsertResult.Existing(
                        authoritative
                )
        );

        assertThatThrownBy(
                () -> recorder.record(
                        proposed
                )
        )
                .isInstanceOf(
                        RiskAssessmentConflictException.class
                );
    }

    @Test
    void rejectsNullProposedAssessment() {
        assertThatThrownBy(
                () -> recorder.record(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "proposedAssessment"
                );
    }

    private RiskAssessment assessment(
            UUID assessmentId,
            int score,
            Instant assessedAt
    ) {
        return new RiskAssessment(
                assessmentId,
                ORGANIZATION_ID,
                GOVERNED_ACTION_ID,
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                new RiskScore(
                        score
                ),
                signals(),
                assessedAt
        );
    }

    private List<RiskSignal> signals() {
        return List.of(
                new RiskSignal(
                        new RiskSignalCode(
                                "BASELINE_TOOL_RISK"
                        ),
                        RiskSeverity.MEDIUM,
                        25,
                        "Tool operation carries baseline runtime risk."
                ),
                new RiskSignal(
                        new RiskSignalCode(
                                "FINANCIAL_IMPACT"
                        ),
                        RiskSeverity.HIGH,
                        40,
                        "Operation has deterministic financial impact."
                )
        );
    }
}