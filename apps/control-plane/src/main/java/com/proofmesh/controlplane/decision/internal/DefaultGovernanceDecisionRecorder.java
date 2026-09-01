package com.proofmesh.controlplane.decision.internal;

import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionCreator;
import com.proofmesh.controlplane.decision.GovernanceDecisionInsertResult;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecorder;
import com.proofmesh.controlplane.decision.GovernanceDecisionRecordingConflictException;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.decision.PolicyEvaluationResult;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
class DefaultGovernanceDecisionRecorder
        implements GovernanceDecisionRecorder {

    private final GovernanceDecisionCreator
            governanceDecisionCreator;

    private final GovernanceDecisionRepository
            governanceDecisionRepository;

    DefaultGovernanceDecisionRecorder(
            GovernanceDecisionCreator
                    governanceDecisionCreator,
            GovernanceDecisionRepository
                    governanceDecisionRepository
    ) {
        this.governanceDecisionCreator =
                Objects.requireNonNull(
                        governanceDecisionCreator,
                        "governanceDecisionCreator must not be null"
                );

        this.governanceDecisionRepository =
                Objects.requireNonNull(
                        governanceDecisionRepository,
                        "governanceDecisionRepository must not be null"
                );
    }

    @Override
    public GovernanceDecision recordAuthoritativeDecision(
            UUID decisionId,
            UUID organizationId,
            UUID governedActionId,
            PolicyEvaluationResult evaluationResult,
            Instant decidedAt
    ) {
        GovernanceDecision candidate =
                governanceDecisionCreator.create(
                        decisionId,
                        organizationId,
                        governedActionId,
                        evaluationResult,
                        decidedAt
                );

        GovernanceDecisionInsertResult insertResult =
                governanceDecisionRepository
                        .insertIfAbsent(
                                candidate
                        );

        return switch (insertResult) {
            case GovernanceDecisionInsertResult.Inserted inserted ->
                    inserted.decision();

            case GovernanceDecisionInsertResult.Existing existing ->
                    resolveExistingDecision(
                            candidate,
                            existing.decision()
                    );
        };
    }

    private GovernanceDecision resolveExistingDecision(
            GovernanceDecision candidate,
            GovernanceDecision existing
    ) {
        if (existing.hasSameDecisionSemanticsAs(
                candidate
        )) {
            return existing;
        }

        throw new GovernanceDecisionRecordingConflictException(
                "governed action already has an authoritative decision with different evaluation semantics"
        );
    }
}