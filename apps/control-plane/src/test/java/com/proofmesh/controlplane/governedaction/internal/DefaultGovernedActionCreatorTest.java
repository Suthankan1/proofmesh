package com.proofmesh.controlplane.governedaction.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.agent.Agent;
import com.proofmesh.controlplane.agent.AgentResolution;
import com.proofmesh.controlplane.agent.AgentResolver;
import com.proofmesh.controlplane.agent.AgentStatus;
import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.CreateGovernedActionCommand;
import com.proofmesh.controlplane.governedaction.CreateGovernedActionResult;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.GovernedActionCreator;
import com.proofmesh.controlplane.governedaction.GovernedActionIdGenerator;
import com.proofmesh.controlplane.governedaction.GovernedActionInsertResult;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import com.proofmesh.controlplane.governedaction.IdempotencyConflictException;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadCanonicalizer;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class DefaultGovernedActionCreatorTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "a0000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "b0000000-0000-0000-0000-000000000001"
            );

    private static final UUID CANDIDATE_ACTION_ID =
            UUID.fromString(
                    "c0000000-0000-0000-0000-000000000001"
            );

    private static final UUID EXISTING_ACTION_ID =
            UUID.fromString(
                    "c0000000-0000-0000-0000-000000000002"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-30T05:00:00Z"
            );

    private AgentResolver agentResolver;

    private RequestPayloadCanonicalizer
            canonicalizer;

    private GovernedActionRepository repository;

    private GovernedActionIdGenerator
            idGenerator;

    private GovernedActionCreator creator;

    @BeforeEach
    void setUp() {
        agentResolver =
                mock(
                        AgentResolver.class
                );

        canonicalizer =
                mock(
                        RequestPayloadCanonicalizer.class
                );

        repository =
                mock(
                        GovernedActionRepository.class
                );

        idGenerator =
                mock(
                        GovernedActionIdGenerator.class
                );

        Clock clock =
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                );

        creator =
                new DefaultGovernedActionCreator(
                        agentResolver,
                        canonicalizer,
                        repository,
                        idGenerator,
                        clock
                );
    }

    @Test
    void createsActionForActiveAgent() {
        Agent agent =
                activeAgent();

        CanonicalRequestPayload payload =
                canonicalPayload(
                        "a".repeat(64)
                );

        CreateGovernedActionCommand command =
                command();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Active(
                        agent
                )
        );

        when(
                canonicalizer.canonicalize(
                        command.requestPayloadJson()
                )
        ).thenReturn(
                payload
        );

        when(
                idGenerator.nextId()
        ).thenReturn(
                CANDIDATE_ACTION_ID
        );

        when(
                repository.insertIfAbsent(
                        org.mockito.ArgumentMatchers.any(
                                GovernedAction.class
                        )
                )
        ).thenAnswer(
                invocation -> {
                    GovernedAction candidate =
                            invocation.getArgument(0);

                    return new GovernedActionInsertResult.Inserted(
                            candidate
                    );
                }
        );

        CreateGovernedActionResult result =
                creator.create(
                        command
                );

        assertThat(result)
                .isInstanceOf(
                        CreateGovernedActionResult
                                .Created.class
                );

        GovernedAction action =
                ((CreateGovernedActionResult.Created)
                        result)
                        .governedAction();

        assertThat(action.id())
                .isEqualTo(
                        CANDIDATE_ACTION_ID
                );

        assertThat(action.organizationId())
                .isEqualTo(
                        ORGANIZATION_ID
                );

        assertThat(action.agentId())
                .isEqualTo(
                        AGENT_ID
                );

        assertThat(action.idempotencyKey())
                .isEqualTo(
                        command.idempotencyKey()
                );

        assertThat(action.toolName())
                .isEqualTo(
                        command.toolName()
                );

        assertThat(action.operationName())
                .isEqualTo(
                        command.operationName()
                );

        assertThat(action.requestPayload())
                .isEqualTo(
                        payload
                );

        assertThat(action.createdAt())
                .isEqualTo(
                        NOW
                );
    }

    @Test
    void returnsExistingActionForSafeRetry() {
        Agent agent =
                activeAgent();

        CanonicalRequestPayload payload =
                canonicalPayload(
                        "a".repeat(64)
                );

        GovernedAction existing =
                existingAction(
                        payload
                );

        CreateGovernedActionCommand command =
                command();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Active(
                        agent
                )
        );

        when(
                canonicalizer.canonicalize(
                        command.requestPayloadJson()
                )
        ).thenReturn(
                payload
        );

        when(
                idGenerator.nextId()
        ).thenReturn(
                CANDIDATE_ACTION_ID
        );

        when(
                repository.insertIfAbsent(
                        org.mockito.ArgumentMatchers.any(
                                GovernedAction.class
                        )
                )
        ).thenReturn(
                new GovernedActionInsertResult.Existing(
                        existing
                )
        );

        CreateGovernedActionResult result =
                creator.create(
                        command
                );

        assertThat(result)
                .isInstanceOf(
                        CreateGovernedActionResult
                                .Existing.class
                );

        GovernedAction returned =
                ((CreateGovernedActionResult.Existing)
                        result)
                        .governedAction();

        assertThat(returned)
                .isEqualTo(
                        existing
                );

        assertThat(returned.id())
                .isEqualTo(
                        EXISTING_ACTION_ID
                );
    }

    @Test
    void rejectsIdempotencyKeyReuseForDifferentPayload() {
        Agent agent =
                activeAgent();

        CanonicalRequestPayload existingPayload =
                canonicalPayload(
                        "a".repeat(64)
                );

        CanonicalRequestPayload incomingPayload =
                canonicalPayload(
                        "b".repeat(64)
                );

        GovernedAction existing =
                existingAction(
                        existingPayload
                );

        CreateGovernedActionCommand command =
                command();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Active(
                        agent
                )
        );

        when(
                canonicalizer.canonicalize(
                        command.requestPayloadJson()
                )
        ).thenReturn(
                incomingPayload
        );

        when(
                idGenerator.nextId()
        ).thenReturn(
                CANDIDATE_ACTION_ID
        );

        when(
                repository.insertIfAbsent(
                        org.mockito.ArgumentMatchers.any(
                                GovernedAction.class
                        )
                )
        ).thenReturn(
                new GovernedActionInsertResult.Existing(
                        existing
                )
        );

        assertThatThrownBy(
                () -> creator.create(
                        command
                )
        )
                .isInstanceOf(
                        IdempotencyConflictException.class
                );
    }

    @Test
    void rejectsIdempotencyKeyReuseForDifferentTool() {
        Agent agent =
                activeAgent();

        CanonicalRequestPayload payload =
                canonicalPayload(
                        "a".repeat(64)
                );

        GovernedAction existing =
                existingAction(
                        payload
                );

        CreateGovernedActionCommand command =
                command(
                        new ToolName(
                                "bank-api"
                        ),
                        new OperationName(
                                "refund_payment"
                        )
                );

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Active(
                        agent
                )
        );

        when(
                canonicalizer.canonicalize(
                        command.requestPayloadJson()
                )
        ).thenReturn(
                payload
        );

        when(
                idGenerator.nextId()
        ).thenReturn(
                CANDIDATE_ACTION_ID
        );

        when(
                repository.insertIfAbsent(
                        org.mockito.ArgumentMatchers.any(
                                GovernedAction.class
                        )
                )
        ).thenReturn(
                new GovernedActionInsertResult.Existing(
                        existing
                )
        );

        assertThatThrownBy(
                () -> creator.create(
                        command
                )
        )
                .isInstanceOf(
                        IdempotencyConflictException.class
                );
    }

    @Test
    void rejectsIdempotencyKeyReuseForDifferentOperation() {
        Agent agent =
                activeAgent();

        CanonicalRequestPayload payload =
                canonicalPayload(
                        "a".repeat(64)
                );

        GovernedAction existing =
                existingAction(
                        payload
                );

        CreateGovernedActionCommand command =
                command(
                        new ToolName(
                                "stripe"
                        ),
                        new OperationName(
                                "capture_payment"
                        )
                );

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Active(
                        agent
                )
        );

        when(
                canonicalizer.canonicalize(
                        command.requestPayloadJson()
                )
        ).thenReturn(
                payload
        );

        when(
                idGenerator.nextId()
        ).thenReturn(
                CANDIDATE_ACTION_ID
        );

        when(
                repository.insertIfAbsent(
                        org.mockito.ArgumentMatchers.any(
                                GovernedAction.class
                        )
                )
        ).thenReturn(
                new GovernedActionInsertResult.Existing(
                        existing
                )
        );

        assertThatThrownBy(
                () -> creator.create(
                        command
                )
        )
                .isInstanceOf(
                        IdempotencyConflictException.class
                );
    }

    @Test
    void rejectsUnknownAgentBeforeProcessingPayload() {
        CreateGovernedActionCommand command =
                command();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Unknown(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        );

        CreateGovernedActionResult result =
                creator.create(
                        command
                );

        assertThat(result)
                .isEqualTo(
                        new CreateGovernedActionResult.Rejected(
                                CreateGovernedActionResult
                                        .RejectionReason
                                        .UNKNOWN_AGENT
                        )
                );

        verifyNoInteractions(
                canonicalizer,
                repository,
                idGenerator
        );
    }

    @Test
    void rejectsDisabledAgentBeforeProcessingPayload() {
        Agent agent =
                new Agent(
                        AGENT_ID,
                        ORGANIZATION_ID,
                        "Finance Refund Agent",
                        AgentStatus.DISABLED,
                        NOW,
                        NOW
                );

        CreateGovernedActionCommand command =
                command();

        when(
                agentResolver.resolve(
                        AGENT_ID,
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new AgentResolution.Disabled(
                        agent
                )
        );

        CreateGovernedActionResult result =
                creator.create(
                        command
                );

        assertThat(result)
                .isEqualTo(
                        new CreateGovernedActionResult.Rejected(
                                CreateGovernedActionResult
                                        .RejectionReason
                                        .AGENT_DISABLED
                        )
                );

        verifyNoInteractions(
                canonicalizer,
                repository,
                idGenerator
        );
    }

    private CreateGovernedActionCommand command() {
        return command(
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                )
        );
    }

    private CreateGovernedActionCommand command(
            ToolName toolName,
            OperationName operationName
    ) {
        return new CreateGovernedActionCommand(
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey(
                        "request-001"
                ),
                toolName,
                operationName,
                """
                {
                  "amount": 5000
                }
                """
        );
    }

    private Agent activeAgent() {
        return new Agent(
                AGENT_ID,
                ORGANIZATION_ID,
                "Finance Refund Agent",
                AgentStatus.ACTIVE,
                NOW,
                NOW
        );
    }

    private CanonicalRequestPayload
    canonicalPayload(
            String hash
    ) {
        return new CanonicalRequestPayload(
                "{\"amount\":5000}",
                new RequestPayloadHash(
                        hash
                )
        );
    }

    private GovernedAction existingAction(
            CanonicalRequestPayload payload
    ) {
        return new GovernedAction(
                EXISTING_ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey(
                        "request-001"
                ),
                new ToolName(
                        "stripe"
                ),
                new OperationName(
                        "refund_payment"
                ),
                payload,
                NOW
        );
    }
}