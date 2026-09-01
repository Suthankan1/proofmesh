package com.proofmesh.controlplane.decision.internal.persistence;

import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionInsertResult;
import com.proofmesh.controlplane.decision.GovernanceDecisionIntegrityException;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaGovernanceDecisionRepository
        implements GovernanceDecisionRepository {

    private final SpringDataGovernanceDecisionJpaRepository
            springDataRepository;

    private final GovernanceDecisionJpaMapper
            mapper;

    JpaGovernanceDecisionRepository(
            SpringDataGovernanceDecisionJpaRepository
                    springDataRepository,
            GovernanceDecisionJpaMapper mapper
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
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GovernanceDecision>
            findByOrganizationIdAndId(
                    UUID organizationId,
                    UUID decisionId
            ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId must not be null"
        );

        Objects.requireNonNull(
                decisionId,
                "decisionId must not be null"
        );

        return springDataRepository
                .findByOrganizationIdAndId(
                        organizationId,
                        decisionId
                )
                .map(
                        mapper::toDomain
                );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GovernanceDecision>
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
    @Transactional
    public GovernanceDecisionInsertResult
            insertIfAbsent(
                    GovernanceDecision decision
            ) {
        Objects.requireNonNull(
                decision,
                "decision must not be null"
        );

        String reasonCodesJson =
                mapper.reasonCodesJson(
                        decision
                );

        UUID matchedPolicyRuleId =
                decision.matchedPolicyRuleId()
                        == null
                        ? null
                        : decision
                                .matchedPolicyRuleId()
                                .value();

        int affectedRows =
                springDataRepository
                        .insertIfAbsent(
                                decision.id(),
                                decision.organizationId(),
                                decision.governedActionId(),
                                decision.policyVersionId()
                                        .value(),
                                matchedPolicyRuleId,
                                decision.outcome()
                                        .name(),
                                decision.riskScore()
                                        .value(),
                                reasonCodesJson,
                                decision.decidedAt()
                        );

        if (affectedRows == 1) {
            return new GovernanceDecisionInsertResult
                    .Inserted(
                            decision
                    );
        }

        if (affectedRows == 0) {
            GovernanceDecision existing =
                    springDataRepository
                            .findByOrganizationIdAndGovernedActionId(
                                    decision.organizationId(),
                                    decision.governedActionId()
                            )
                            .map(
                                    mapper::toDomain
                            )
                            .orElseThrow(
                                    () -> new GovernanceDecisionIntegrityException(
                                            "governance decision conflict occurred but the existing decision could not be loaded"
                                    )
                            );

            return new GovernanceDecisionInsertResult
                    .Existing(
                            existing
                    );
        }

        throw new GovernanceDecisionIntegrityException(
                "unexpected governance decision insert row count: "
                        + affectedRows
        );
    }
}