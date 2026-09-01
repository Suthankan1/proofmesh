package com.proofmesh.controlplane.runtimegovernance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentConflictException;
import com.proofmesh.controlplane.risk.RiskAssessmentRecorder;
import com.proofmesh.controlplane.risk.RiskAssessmentRequest;
import com.proofmesh.controlplane.risk.RiskAssessor;
import com.proofmesh.controlplane.risk.RiskLogicVersion;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultRuntimeRiskAssessmentResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "e1000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "e2000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "e3000000-0000-0000-0000-000000000001"
            );

    private static final UUID ASSESSMENT_ID =
            UUID.fromString(
                    "e4000000-0000-0000-0000-000000000001"
            );

    private static final UUID AUTHORITATIVE_ASSESSMENT_ID =
            UUID.fromString(
                    "e4000000-0000-0000-0000-000000000002"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "e5000000-0000-0000-0000-000000000001"
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-01T10:00:00Z"
            );

    private RiskAssessor riskAssessor;

    private RiskAssessmentRecorder riskAssessmentRecorder;

    private RuntimeRiskAssessmentResolver resolver;

    @BeforeEach
    void setUp() {
        riskAssessor =
                mock(
                        RiskAssessor.class
                );

        riskAssessmentRecorder =
                mock(
                        RiskAssessmentRecorder.class
                );

        resolver =
                new DefaultRuntimeRiskAssessmentResolver(
                        riskAssessor,
                        riskAssessmentRecorder
                );
    }

    @Test
    void assessesAndReturnsAuthoritativeRiskAssessment() {
        RuntimeGovernanceRequest request =
                request();

        RiskAssessment proposed =
                assessment(
                        ASSESSMENT_ID,
                        EVALUATED_AT
                );

        RiskAssessment authoritative =
                assessment(
                        AUTHORITATIVE_ASSESSMENT_ID,
                        EVALUATED_AT.plusMillis(
                                5
                        )
                );

        when(
                riskAssessor.assess(
                        any(
                                RiskAssessmentRequest.class
                        )
                )
        ).thenReturn(
                proposed
        );

        when(
                riskAssessmentRecorder.record(
                        proposed
                )
        ).thenReturn(
                authoritative
        );

        RuntimeRiskAssessmentResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isInstanceOf(
                        RuntimeRiskAssessmentResolution
                                .Ready.class
                );

        RuntimeRiskAssessmentResolution.Ready ready =
                (RuntimeRiskAssessmentResolution.Ready)
                        result;

        assertThat(
                ready.riskAssessment()
        ).isSameAs(
                authoritative
        );
    }

    @Test
    void constructsRiskAssessmentRequestFromExactGovernedAction() {
        RuntimeGovernanceRequest request =
                request();

        RiskAssessment proposed =
                assessment(
                        ASSESSMENT_ID,
                        EVALUATED_AT
                );

        when(
                riskAssessor.assess(
                        any(
                                RiskAssessmentRequest.class
                        )
                )
        ).thenReturn(
                proposed
        );

        when(
                riskAssessmentRecorder.record(
                        proposed
                )
        ).thenReturn(
                proposed
        );

        resolver.resolve(
                request
        );

        ArgumentCaptor<RiskAssessmentRequest> captor =
                ArgumentCaptor.forClass(
                        RiskAssessmentRequest.class
                );

        verify(
                riskAssessor
        ).assess(
                captor.capture()
        );

        RiskAssessmentRequest captured =
                captor.getValue();

        assertThat(
                captured.assessmentId()
        ).isEqualTo(
                ASSESSMENT_ID
        );

        assertThat(
                captured.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                captured.governedActionId()
        ).isEqualTo(
                ACTION_ID
        );

        assertThat(
                captured.signals()
        ).containsExactlyElementsOf(
                request.riskSignals()
        );

        assertThat(
                captured.assessedAt()
        ).isEqualTo(
                EVALUATED_AT
        );
    }

    @Test
    void recordsProposedAssessmentBeforeReturningSuccess() {
        RuntimeGovernanceRequest request =
                request();

        RiskAssessment proposed =
                assessment(
                        ASSESSMENT_ID,
                        EVALUATED_AT
                );

        when(
                riskAssessor.assess(
                        any(
                                RiskAssessmentRequest.class
                        )
                )
        ).thenReturn(
                proposed
        );

        when(
                riskAssessmentRecorder.record(
                        proposed
                )
        ).thenReturn(
                proposed
        );

        resolver.resolve(
                request
        );

        verify(
                riskAssessmentRecorder
        ).record(
                proposed
        );
    }

    @Test
    void riskAssessmentConflictFailsClosed() {
        RuntimeGovernanceRequest request =
                request();

        RiskAssessment proposed =
                assessment(
                        ASSESSMENT_ID,
                        EVALUATED_AT
                );

        when(
                riskAssessor.assess(
                        any(
                                RiskAssessmentRequest.class
                        )
                )
        ).thenReturn(
                proposed
        );

        when(
                riskAssessmentRecorder.record(
                        proposed
                )
        ).thenThrow(
                new RiskAssessmentConflictException(
                        "conflicting authoritative assessment"
                )
        );

        RuntimeRiskAssessmentResolution result =
                resolver.resolve(
                        request
                );

        assertThat(result)
                .isEqualTo(
                        new RuntimeRiskAssessmentResolution.Failed(
                                RuntimeGovernanceFailureReason
                                        .RISK_ASSESSMENT_CONFLICT
                        )
                );
    }

    @Test
    void emptyRuntimeSignalsArePassedToFailClosedRiskAssessor() {
        RuntimeGovernanceRequest request =
                requestWithSignals(
                        List.of()
                );

        RiskAssessment proposed =
                assessmentWithSignals(
                        ASSESSMENT_ID,
                        EVALUATED_AT,
                        List.of(
                                new RiskSignal(
                                        new RiskSignalCode(
                                                "UNCLASSIFIED_OPERATION"
                                        ),
                                        RiskSeverity.CRITICAL,
                                        100,
                                        "No deterministic risk metadata was available for the governed action."
                                )
                        ),
                        100
                );

        when(
                riskAssessor.assess(
                        any(
                                RiskAssessmentRequest.class
                        )
                )
        ).thenReturn(
                proposed
        );

        when(
                riskAssessmentRecorder.record(
                        proposed
                )
        ).thenReturn(
                proposed
        );

        resolver.resolve(
                request
        );

        ArgumentCaptor<RiskAssessmentRequest> captor =
                ArgumentCaptor.forClass(
                        RiskAssessmentRequest.class
                );

        verify(
                riskAssessor
        ).assess(
                captor.capture()
        );

        assertThat(
                captor.getValue().signals()
        ).isEmpty();
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(
                () -> resolver.resolve(
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

    private RuntimeGovernanceRequest request() {
        return requestWithSignals(
                signals()
        );
    }

    private RuntimeGovernanceRequest requestWithSignals(
            List<RiskSignal> signals
    ) {
        GovernedAction governedAction =
                mock(
                        GovernedAction.class
                );

        when(
                governedAction.id()
        ).thenReturn(
                ACTION_ID
        );

        when(
                governedAction.organizationId()
        ).thenReturn(
                ORGANIZATION_ID
        );

        when(
                governedAction.agentId()
        ).thenReturn(
                AGENT_ID
        );

        return new RuntimeGovernanceRequest(
                ASSESSMENT_ID,
                DECISION_ID,
                governedAction,
                signals,
                EVALUATED_AT
        );
    }

    private RiskAssessment assessment(
            UUID id,
            Instant assessedAt
    ) {
        return assessmentWithSignals(
                id,
                assessedAt,
                signals(),
                65
        );
    }

    private RiskAssessment assessmentWithSignals(
            UUID id,
            Instant assessedAt,
            List<RiskSignal> signals,
            int score
    ) {
        return new RiskAssessment(
                id,
                ORGANIZATION_ID,
                ACTION_ID,
                new RiskLogicVersion(
                        "deterministic-v1"
                ),
                new RiskScore(
                        score
                ),
                signals,
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