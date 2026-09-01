package com.proofmesh.controlplane.decision.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionCreator;
import com.proofmesh.controlplane.decision.GovernanceDecisionInsertResult;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecorder;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecordingConflictException;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class DefaultGovernanceDecisionRecorderTest {

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "e2000000-0000-0000-0000-000000000001"
            );

    private static final UUID EXISTING_DECISION_ID =
            UUID.fromString(
                    "e2000000-0000-0000-0000-000000000002"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "e3000000-0000-0000-0000-000000000001"
            );

    private static final UUID GOVERNED_ACTION_ID =
            UUID.fromString(
                    "e4000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "e5000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "e6000000-0000-0000-0000-000000000001"
                    )
            );

    private static final Instant DECIDED_AT =
            Instant.parse(
                    "2026-09-01T02:00:00Z"
            );

    private GovernanceDecisionCreator creator;

    @BeforeEach
    void setUp() {
        creator =
                new DefaultGovernanceDecisionCreator();
    }

    @Test
    void returnsNewDecisionWhenInsertWins() {
        InMemoryDecisionRepository repository =
                new InMemoryDecisionRepository();

        GovernanceDecisionRecorder recorder =
                new DefaultGovernanceDecisionRecorder(
                        creator,
                        repository
                );

        GovernanceDecision recorded =
                recorder.recordAuthoritativeDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        matchedEvaluation(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        DECIDED_AT
                );

        assertThat(
                recorded.id()
        ).isEqualTo(
                DECISION_ID
        );

        assertThat(
                recorded.outcome()
        ).isEqualTo(
                DecisionOutcome.REQUIRE_APPROVAL
        );
    }

    @Test
    void returnsExistingWinnerForEquivalentRetry() {
        GovernanceDecision existing =
                creator.create(
                        EXISTING_DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        matchedEvaluation(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        DECIDED_AT
                );

        InMemoryDecisionRepository repository =
                new InMemoryDecisionRepository(
                        existing
                );

        GovernanceDecisionRecorder recorder =
                new DefaultGovernanceDecisionRecorder(
                        creator,
                        repository
                );

        GovernanceDecision authoritative =
                recorder.recordAuthoritativeDecision(
                        DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        matchedEvaluation(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        DECIDED_AT.plusSeconds(10)
                );

        assertThat(
                authoritative
        ).isEqualTo(
                existing
        );

        assertThat(
                authoritative.id()
        ).isEqualTo(
                EXISTING_DECISION_ID
        );
    }

    @Test
    void rejectsRetryWhoseOutcomeConflictsWithAuthoritativeDecision() {
        GovernanceDecision existing =
                creator.create(
                        EXISTING_DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        matchedEvaluation(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        DECIDED_AT
                );

        InMemoryDecisionRepository repository =
                new InMemoryDecisionRepository(
                        existing
                );

        GovernanceDecisionRecorder recorder =
                new DefaultGovernanceDecisionRecorder(
                        creator,
                        repository
                );

        assertThatThrownBy(
                () -> recorder
                        .recordAuthoritativeDecision(
                                DECISION_ID,
                                ORGANIZATION_ID,
                                GOVERNED_ACTION_ID,
                                matchedEvaluation(
                                        DecisionOutcome.ALLOW
                                ),
                                DECIDED_AT.plusSeconds(10)
                        )
        )
                .isInstanceOf(
                        GovernanceDecisionRecordingConflictException.class
                )
                .hasMessageContaining(
                        "different evaluation semantics"
                );
    }

    @Test
    void rejectsRetryWhoseRiskScoreConflictsWithAuthoritativeDecision() {
        GovernanceDecision existing =
                new GovernanceDecision(
                        EXISTING_DECISION_ID,
                        ORGANIZATION_ID,
                        GOVERNED_ACTION_ID,
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(90),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        ),
                        DECIDED_AT
                );

        InMemoryDecisionRepository repository =
                new InMemoryDecisionRepository(
                        existing
                );

        GovernanceDecisionRecorder recorder =
                new DefaultGovernanceDecisionRecorder(
                        creator,
                        repository
                );

        PolicyEvaluationResult conflictingEvaluation =
                new PolicyEvaluationResult.Matched(
                        POLICY_VERSION_ID,
                        POLICY_RULE_ID,
                        DecisionOutcome.REQUIRE_APPROVAL,
                        new RiskScore(95),
                        List.of(
                                new DecisionReasonCode(
                                        "HIGH_RISK_REFUND"
                                )
                        )
                );

        assertThatThrownBy(
                () -> recorder
                        .recordAuthoritativeDecision(
                                DECISION_ID,
                                ORGANIZATION_ID,
                                GOVERNED_ACTION_ID,
                                conflictingEvaluation,
                                DECIDED_AT.plusSeconds(10)
                        )
        )
                .isInstanceOf(
                        GovernanceDecisionRecordingConflictException.class
                );
    }

    private PolicyEvaluationResult matchedEvaluation(
            DecisionOutcome outcome
    ) {
        return new PolicyEvaluationResult.Matched(
                POLICY_VERSION_ID,
                POLICY_RULE_ID,
                outcome,
                new RiskScore(90),
                List.of(
                        new DecisionReasonCode(
                                "HIGH_RISK_REFUND"
                        )
                )
        );
    }

    private static final class InMemoryDecisionRepository
            implements GovernanceDecisionRepository {

        private GovernanceDecision stored;

        private InMemoryDecisionRepository() {
        }

        private InMemoryDecisionRepository(
                GovernanceDecision stored
        ) {
            this.stored = stored;
        }

        @Override
        public Optional<GovernanceDecision>
                findByOrganizationIdAndId(
                        UUID organizationId,
                        UUID decisionId
                ) {
            if (stored == null
                    || !stored.organizationId()
                            .equals(
                                    organizationId
                            )
                    || !stored.id()
                            .equals(
                                    decisionId
                            )) {
                return Optional.empty();
            }

            return Optional.of(
                    stored
            );
        }

        @Override
        public Optional<GovernanceDecision>
                findByOrganizationIdAndGovernedActionId(
                        UUID organizationId,
                        UUID governedActionId
                ) {
            if (stored == null
                    || !stored.organizationId()
                            .equals(
                                    organizationId
                            )
                    || !stored.governedActionId()
                            .equals(
                                    governedActionId
                            )) {
                return Optional.empty();
            }

            return Optional.of(
                    stored
            );
        }

        @Override
        public GovernanceDecisionInsertResult insertIfAbsent(
                GovernanceDecision decision
        ) {
            if (stored == null) {
                stored = decision;

                return new GovernanceDecisionInsertResult
                        .Inserted(
                                decision
                        );
            }

            return new GovernanceDecisionInsertResult
                    .Existing(
                            stored
                    );
        }
    }
}