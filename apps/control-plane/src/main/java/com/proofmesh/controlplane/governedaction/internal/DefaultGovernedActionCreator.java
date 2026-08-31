package com.proofmesh.controlplane.governedaction.internal;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentResolution;
import com.proofmesh.controlplane.agent.AgentResolver;
import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.CreateGovernedActionCommand;
import com.proofmesh.controlplane.governedaction.CreateGovernedActionResult;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.GovernedActionCreator;
import com.proofmesh.controlplane.governedaction.GovernedActionIdGenerator;
import com.proofmesh.controlplane.governedaction.GovernedActionInsertResult;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import com.proofmesh.controlplane.governedaction.IdempotencyConflictException;
import com.proofmesh.controlplane.governedaction.RequestPayloadCanonicalizer;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
class DefaultGovernedActionCreator
        implements GovernedActionCreator {

    private final AgentResolver agentResolver;

    private final RequestPayloadCanonicalizer
            requestPayloadCanonicalizer;

    private final GovernedActionRepository
            governedActionRepository;

    private final GovernedActionIdGenerator
            idGenerator;

    private final Clock clock;

    DefaultGovernedActionCreator(
            AgentResolver agentResolver,
            RequestPayloadCanonicalizer
                    requestPayloadCanonicalizer,
            GovernedActionRepository
                    governedActionRepository,
            GovernedActionIdGenerator idGenerator,
            Clock clock
    ) {
        this.agentResolver =
                Objects.requireNonNull(
                        agentResolver,
                        "agentResolver must not be null"
                );

        this.requestPayloadCanonicalizer =
                Objects.requireNonNull(
                        requestPayloadCanonicalizer,
                        "requestPayloadCanonicalizer must not be null"
                );

        this.governedActionRepository =
                Objects.requireNonNull(
                        governedActionRepository,
                        "governedActionRepository must not be null"
                );

        this.idGenerator =
                Objects.requireNonNull(
                        idGenerator,
                        "idGenerator must not be null"
                );

        this.clock =
                Objects.requireNonNull(
                        clock,
                        "clock must not be null"
                );
    }

    @Override
    public CreateGovernedActionResult create(
            CreateGovernedActionCommand command
    ) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        AgentResolution agentResolution =
                agentResolver.resolve(
                        command.agentId(),
                        command.organizationId()
                );

        if (agentResolution
                instanceof AgentResolution.Unknown) {
            return new CreateGovernedActionResult.Rejected(
                    CreateGovernedActionResult
                            .RejectionReason
                            .UNKNOWN_AGENT
            );
        }

        if (agentResolution
                instanceof AgentResolution.Disabled) {
            return new CreateGovernedActionResult.Rejected(
                    CreateGovernedActionResult
                            .RejectionReason
                            .AGENT_DISABLED
            );
        }

        Agent agent =
                ((AgentResolution.Active)
                        agentResolution)
                        .agent();

        CanonicalRequestPayload requestPayload =
                requestPayloadCanonicalizer
                        .canonicalize(
                                command.requestPayloadJson()
                        );

        GovernedAction candidate =
                new GovernedAction(
                        idGenerator.nextId(),
                        agent.organizationId(),
                        agent.id(),
                        command.idempotencyKey(),
                        command.toolName(),
                        command.operationName(),
                        requestPayload,
                        Instant.now(clock)
                );

        GovernedActionInsertResult insertResult =
                governedActionRepository
                        .insertIfAbsent(
                                candidate
                        );

        return switch (insertResult) {
            case GovernedActionInsertResult.Inserted inserted ->
                    new CreateGovernedActionResult.Created(
                            inserted.governedAction()
                    );

            case GovernedActionInsertResult.Existing existing ->
                    handleExisting(
                            existing.governedAction(),
                            command,
                            requestPayload
                    );
        };
    }

    private CreateGovernedActionResult
    handleExisting(
            GovernedAction existing,
            CreateGovernedActionCommand command,
            CanonicalRequestPayload incomingPayload
    ) {
        if (!existing.matchesRequest(
                command.toolName(),
                command.operationName(),
                incomingPayload.hash()
        )) {
            throw new IdempotencyConflictException();
        }

        return new CreateGovernedActionResult.Existing(
                existing
        );
    }
}