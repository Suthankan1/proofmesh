package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantAuthorizationContext;
import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

class DefaultExecutionGrantAuthorizationContextResolverTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000001");

    private static final UUID AGENT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000001");

    private static final UUID ACTION_ID =
            UUID.fromString("73000000-0000-0000-0000-000000000001");

    private static final UUID DECISION_ID =
            UUID.fromString("74000000-0000-0000-0000-000000000001");

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString("75000000-0000-0000-0000-000000000001");

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(UUID.fromString("76000000-0000-0000-0000-000000000001"));

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(UUID.fromString("77000000-0000-0000-0000-000000000001"));

    private static final ToolName TOOL_NAME =
            new ToolName("stripe");

    private static final OperationName OPERATION_NAME =
            new OperationName("refund_payment");

    private static final RequestPayloadHash REQUEST_PAYLOAD_HASH =
            new RequestPayloadHash("a".repeat(64));

    private static final Instant CREATED_AT =
            Instant.parse("2026-09-02T10:00:00Z");

    private static final Instant EXPIRES_AT =
            CREATED_AT.plus(Duration.ofMinutes(15));

    private GovernedActionRepository actionRepository;
    private GovernanceDecisionRepository decisionRepository;
    private ApprovalRequestRepository approvalRepository;
    private DefaultExecutionGrantAuthorizationContextResolver resolver;

    @BeforeEach
    void setUp() {
        actionRepository = mock(GovernedActionRepository.class);
        decisionRepository = mock(GovernanceDecisionRepository.class);
        approvalRepository = mock(ApprovalRequestRepository.class);
        resolver = new DefaultExecutionGrantAuthorizationContextResolver(
                actionRepository,
                decisionRepository,
                approvalRepository
        );
    }

    @Test
    void rejectsNullConstructorDependencies() {
        assertThatThrownBy(() -> new DefaultExecutionGrantAuthorizationContextResolver(
                null, decisionRepository, approvalRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("actionRepository must not be null");

        assertThatThrownBy(() -> new DefaultExecutionGrantAuthorizationContextResolver(
                actionRepository, null, approvalRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("decisionRepository must not be null");

        assertThatThrownBy(() -> new DefaultExecutionGrantAuthorizationContextResolver(
                actionRepository, decisionRepository, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("approvalRepository must not be null");
    }

    @Test
    void resolvesContextForAllowOutcomeWithoutQueryingApproval() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.ALLOW);

        when(actionRepository.findByIdAndOrganizationId(ACTION_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(action));
        when(decisionRepository.findByOrganizationIdAndGovernedActionId(ORGANIZATION_ID, ACTION_ID))
                .thenReturn(Optional.of(decision));

        Optional<ExecutionGrantAuthorizationContext> result =
                resolver.resolve(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isPresent();
        ExecutionGrantAuthorizationContext context = result.get();
        assertThat(context.governedAction()).isSameAs(action);
        assertThat(context.governanceDecision()).isSameAs(decision);
        assertThat(context.approvalRequest()).isEmpty();

        verify(approvalRepository, never())
                .findByOrganizationIdAndGovernanceDecisionId(any(), any());
    }

    @Test
    void doesNotQueryApprovalForDenyOutcome() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.DENY);

        when(actionRepository.findByIdAndOrganizationId(ACTION_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(action));
        when(decisionRepository.findByOrganizationIdAndGovernedActionId(ORGANIZATION_ID, ACTION_ID))
                .thenReturn(Optional.of(decision));

        Optional<ExecutionGrantAuthorizationContext> result =
                resolver.resolve(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isPresent();
        ExecutionGrantAuthorizationContext context = result.get();
        assertThat(context.governedAction()).isSameAs(action);
        assertThat(context.governanceDecision()).isSameAs(decision);
        assertThat(context.approvalRequest()).isEmpty();

        verify(approvalRepository, never())
                .findByOrganizationIdAndGovernanceDecisionId(any(), any());
    }

    @Test
    void resolvesContextWithApprovalWhenDecisionRequiresApprovalAndApprovalPresent() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.REQUIRE_APPROVAL);
        ApprovalRequest approval = approvalRequest();

        when(actionRepository.findByIdAndOrganizationId(ACTION_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(action));
        when(decisionRepository.findByOrganizationIdAndGovernedActionId(ORGANIZATION_ID, ACTION_ID))
                .thenReturn(Optional.of(decision));
        when(approvalRepository.findByOrganizationIdAndGovernanceDecisionId(ORGANIZATION_ID, DECISION_ID))
                .thenReturn(Optional.of(approval));

        Optional<ExecutionGrantAuthorizationContext> result =
                resolver.resolve(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isPresent();
        ExecutionGrantAuthorizationContext context = result.get();
        assertThat(context.governedAction()).isSameAs(action);
        assertThat(context.governanceDecision()).isSameAs(decision);
        assertThat(context.approvalRequest()).containsSame(approval);

        verify(approvalRepository)
                .findByOrganizationIdAndGovernanceDecisionId(ORGANIZATION_ID, DECISION_ID);
    }

    @Test
    void resolvesContextWithEmptyApprovalWhenDecisionRequiresApprovalAndApprovalAbsent() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.REQUIRE_APPROVAL);

        when(actionRepository.findByIdAndOrganizationId(ACTION_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(action));
        when(decisionRepository.findByOrganizationIdAndGovernedActionId(ORGANIZATION_ID, ACTION_ID))
                .thenReturn(Optional.of(decision));
        when(approvalRepository.findByOrganizationIdAndGovernanceDecisionId(ORGANIZATION_ID, DECISION_ID))
                .thenReturn(Optional.empty());

        Optional<ExecutionGrantAuthorizationContext> result =
                resolver.resolve(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isPresent();
        ExecutionGrantAuthorizationContext context = result.get();
        assertThat(context.governedAction()).isSameAs(action);
        assertThat(context.governanceDecision()).isSameAs(decision);
        assertThat(context.approvalRequest()).isEmpty();
    }

    @Test
    void returnsEmptyWhenGovernedActionIsAbsent() {
        when(actionRepository.findByIdAndOrganizationId(ACTION_ID, ORGANIZATION_ID))
                .thenReturn(Optional.empty());

        Optional<ExecutionGrantAuthorizationContext> result =
                resolver.resolve(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(decisionRepository);
        verifyNoInteractions(approvalRepository);
    }

    @Test
    void returnsEmptyWhenGovernanceDecisionIsAbsent() {
        GovernedAction action = governedAction();
        when(actionRepository.findByIdAndOrganizationId(ACTION_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(action));
        when(decisionRepository.findByOrganizationIdAndGovernedActionId(ORGANIZATION_ID, ACTION_ID))
                .thenReturn(Optional.empty());

        Optional<ExecutionGrantAuthorizationContext> result =
                resolver.resolve(ORGANIZATION_ID, ACTION_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(approvalRepository);
    }

    @Test
    void rejectsNullOrganizationId() {
        assertThatThrownBy(() -> resolver.resolve(null, ACTION_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("organizationId must not be null");
    }

    @Test
    void rejectsNullGovernedActionId() {
        assertThatThrownBy(() -> resolver.resolve(ORGANIZATION_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("governedActionId must not be null");
    }

    @Test
    void passesExactTenantScopedIdentifiersToRepositories() {
        GovernedAction action = governedAction();
        GovernanceDecision decision = governanceDecision(DecisionOutcome.REQUIRE_APPROVAL);
        ApprovalRequest approval = approvalRequest();

        when(actionRepository.findByIdAndOrganizationId(ACTION_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(action));
        when(decisionRepository.findByOrganizationIdAndGovernedActionId(ORGANIZATION_ID, ACTION_ID))
                .thenReturn(Optional.of(decision));
        when(approvalRepository.findByOrganizationIdAndGovernanceDecisionId(ORGANIZATION_ID, DECISION_ID))
                .thenReturn(Optional.of(approval));

        resolver.resolve(ORGANIZATION_ID, ACTION_ID);

        verify(actionRepository).findByIdAndOrganizationId(ACTION_ID, ORGANIZATION_ID);
        verify(decisionRepository).findByOrganizationIdAndGovernedActionId(ORGANIZATION_ID, ACTION_ID);
        verify(approvalRepository).findByOrganizationIdAndGovernanceDecisionId(ORGANIZATION_ID, DECISION_ID);
    }

    @Test
    void resolverDependenciesNeverIncludePolicyBindingRiskOrRuntimeGovernance() {
        Set<Class<?>> fieldTypes = Arrays.stream(DefaultExecutionGrantAuthorizationContextResolver.class.getDeclaredFields())
                .map(Field::getType)
                .collect(Collectors.toSet());

        assertThat(fieldTypes).containsExactlyInAnyOrder(
                GovernedActionRepository.class,
                GovernanceDecisionRepository.class,
                ApprovalRequestRepository.class
        );
    }

    private static GovernedAction governedAction() {
        return new GovernedAction(
                ACTION_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                new IdempotencyKey("context-test-key"),
                TOOL_NAME,
                OPERATION_NAME,
                new CanonicalRequestPayload("{\"amount\":100}", REQUEST_PAYLOAD_HASH),
                CREATED_AT
        );
    }

    private static GovernanceDecision governanceDecision(DecisionOutcome outcome) {
        PolicyRuleId matchedRule = outcome == DecisionOutcome.DENY ? null : POLICY_RULE_ID;
        return new GovernanceDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                matchedRule,
                outcome,
                new RiskScore(50),
                List.of(new DecisionReasonCode("REASON_CODE")),
                CREATED_AT
        );
    }

    private static ApprovalRequest approvalRequest() {
        return new ApprovalRequest(
                APPROVAL_REQUEST_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                DECISION_ID,
                AGENT_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                CREATED_AT,
                EXPIRES_AT,
                new ApprovalState.Approved(
                        new ApprovalActorId("operator-001"),
                        new ApprovalRationale("Approved"),
                        CREATED_AT.plusSeconds(30)
                )
        );
    }
}
