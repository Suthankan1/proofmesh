package com.proofmesh.controlplane.governedaction.internal.persistence;

import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.GovernedActionInsertResult;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.RequestPayloadCanonicalizer;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaGovernedActionRepository
        implements GovernedActionRepository {

    private final SpringDataGovernedActionRepository
            springDataRepository;

    private final RequestPayloadCanonicalizer
            requestPayloadCanonicalizer;

    JpaGovernedActionRepository(
            SpringDataGovernedActionRepository
                    springDataRepository,
            RequestPayloadCanonicalizer
                    requestPayloadCanonicalizer
    ) {
        this.springDataRepository =
                Objects.requireNonNull(
                        springDataRepository,
                        "springDataRepository must not be null"
                );

        this.requestPayloadCanonicalizer =
                Objects.requireNonNull(
                        requestPayloadCanonicalizer,
                        "requestPayloadCanonicalizer must not be null"
                );
    }

    @Override
    @Transactional
    public GovernedActionInsertResult
    insertIfAbsent(
            GovernedAction governedAction
    ) {
        Objects.requireNonNull(
                governedAction,
                "governedAction must not be null"
        );

        int inserted =
                springDataRepository
                        .insertIfAbsent(
                                governedAction.id(),
                                governedAction.organizationId(),
                                governedAction.agentId(),
                                governedAction
                                        .idempotencyKey()
                                        .value(),
                                governedAction
                                        .toolName()
                                        .value(),
                                governedAction
                                        .operationName()
                                        .value(),
                                governedAction
                                        .requestPayload()
                                        .canonicalJson(),
                                governedAction
                                        .requestPayloadHash()
                                        .value(),
                                governedAction.createdAt()
                        );

        if (inserted == 1) {
            return new GovernedActionInsertResult
                    .Inserted(
                    governedAction
            );
        }

        if (inserted != 0) {
            throw new IllegalStateException(
                    "Unexpected governed action insert count: "
                            + inserted
            );
        }

        GovernedAction existing =
                springDataRepository
                        .findByOrganizationIdAndAgentIdAndIdempotencyKey(
                                governedAction.organizationId(),
                                governedAction.agentId(),
                                governedAction
                                        .idempotencyKey()
                                        .value()
                        )
                        .map(
                                entity ->
                                        entity.toDomain(
                                                requestPayloadCanonicalizer
                                        )
                        )
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Idempotency conflict occurred but the existing governed action could not be loaded"
                                )
                        );

        return new GovernedActionInsertResult
                .Existing(
                existing
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GovernedAction>
    findByIdAndOrganizationId(
            UUID governedActionId,
            UUID organizationId
    ) {
        return springDataRepository
                .findByIdAndOrganizationId(
                        governedActionId,
                        organizationId
                )
                .map(
                        entity ->
                                entity.toDomain(
                                        requestPayloadCanonicalizer
                                )
                );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GovernedAction>
    findByOrganizationIdAndAgentIdAndIdempotencyKey(
            UUID organizationId,
            UUID agentId,
            IdempotencyKey idempotencyKey
    ) {
        return springDataRepository
                .findByOrganizationIdAndAgentIdAndIdempotencyKey(
                        organizationId,
                        agentId,
                        idempotencyKey.value()
                )
                .map(
                        entity ->
                                entity.toDomain(
                                        requestPayloadCanonicalizer
                                )
                );
    }
}