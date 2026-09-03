package com.proofmesh.controlplane.executiongrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.approval.ApprovalActorId;
import com.proofmesh.controlplane.approval.ApprovalRationale;
import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.approval.ApprovalState;
import com.proofmesh.controlplane.decision.DecisionOutcome;
import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.governedaction.IdempotencyKey;
import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.RequestPayloadHash;
import com.proofmesh.controlplane.governedaction.ToolName;
import com.proofmesh.controlplane.policy.AgentPolicyBinding;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskLogicVersion;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceFailureReason;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceResult;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class ExecutionGrantClaimsPreparerTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "81000000-0000-0000-0000-000000000001"
            );

    private static final UUID AGENT_ID =
            UUID.fromString(
                    "82000000-0000-0000-0000-000000000001"
            );

    private static final UUID ACTION_ID =
            UUID.fromString(
                    "83000000-0000-0000-0000-000000000001"
            );

    private static final UUID DECISION_ID =
            UUID.fromString(
                    "84000000-0000-0000-0000-000000000001"
            );

    private static final UUID POLICY_BINDING_ID =
            UUID.fromString(
                    "85000000-0000-0000-0000-000000000001"
            );

    private static final UUID RISK_ASSESSMENT_ID =
            UUID.fromString(
                    "86000000-0000-0000-0000-000000000001"
            );

    private static final UUID APPROVAL_REQUEST_ID =
            UUID.fromString(
                    "87000000-0000-0000-0000-000000000001"
            );

    private static final PolicyVersionId POLICY_VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "88000000-0000-0000-0000-000000000001"
                    )
            );

    private static final PolicyRuleId POLICY_RULE_ID =
            new PolicyRuleId(
                    UUID.fromString(
                            "89000000-0000-0000-0000-000000000001"
                    )
            );

    private static final ToolName TOOL_NAME =
            new ToolName(
                    "stripe"
            );

    private static final OperationName OPERATION_NAME =
            new OperationName(
                    "refund_payment"
            );

    private static final RequestPayloadHash REQUEST_PAYLOAD_HASH =
            new RequestPayloadHash(
                    "a".repeat(
                            64
                    )
            );

    private static final String ISSUER =
            "proofmesh-control-plane";

    private static final String AUDIENCE =
            "proofmesh-gateway";

    private static final Duration GRANT_TTL =
            Duration.ofSeconds(
                    30
            );

    private static final ExecutionGrantIssuancePolicy POLICY =
            new ExecutionGrantIssuancePolicy(
                    ISSUER,
                    AUDIENCE,
                    GRANT_TTL
            );

    private static final Instant EVALUATED_AT =
            Instant.parse(
                    "2026-09-02T10:00:00Z"
            );

    private static final Instant APPROVED_AT =
            EVALUATED_AT.plus(
                    Duration.ofMinutes(
                            1
                    )
            );

    private static final Instant APPROVAL_EXPIRES_AT =
            EVALUATED_AT.plus(
                    Duration.ofMinutes(
                            15
                    )
            );

    private static final ExecutionGrantId DEFAULT_GRANT_ID =
            new ExecutionGrantId(
                    UUID.fromString(
                            "8a000000-0000-0000-0000-000000000001"
                    )
            );

    private final ExecutionGrantEligibilityEvaluator eligibilityEvaluator =
            new ExecutionGrantEligibilityEvaluator();

    @Test
    void exactAllowPreparesClaimsWithExactAuthoritativeProvenance() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantClaimsPreparationResult.Prepared.class
                );

        ExecutionGrantClaimsPreparationResult.Prepared prepared =
                (ExecutionGrantClaimsPreparationResult.Prepared)
                        result;

        ExecutionGrantClaims claims =
                prepared.claims();

        assertThat(claims.grantId())
                .isEqualTo(DEFAULT_GRANT_ID);
        assertThat(claims.organizationId())
                .isEqualTo(ORGANIZATION_ID);
        assertThat(claims.agentId())
                .isEqualTo(AGENT_ID);
        assertThat(claims.governedActionId())
                .isEqualTo(ACTION_ID);
        assertThat(claims.governanceDecisionId())
                .isEqualTo(DECISION_ID);
        assertThat(claims.toolName())
                .isEqualTo(TOOL_NAME);
        assertThat(claims.operationName())
                .isEqualTo(OPERATION_NAME);
        assertThat(claims.requestPayloadHash())
                .isEqualTo(REQUEST_PAYLOAD_HASH);
        assertThat(claims.issuer())
                .isEqualTo(ISSUER);
        assertThat(claims.audience())
                .isEqualTo(AUDIENCE);
        assertThat(claims.issuedAt())
                .isEqualTo(APPROVED_AT);
        assertThat(claims.expiresAt())
                .isEqualTo(
                        APPROVED_AT.plus(GRANT_TTL)
                );

        assertThat(generator.invocationCount())
                .isEqualTo(1);
    }

    @Test
    void requireApprovalWithValidApprovalWhereRemainingLifetimeLongerThanTtlUsesPolicyExpiry() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        // Approval expires 15 minutes after EVALUATED_AT; at APPROVED_AT (1 min in), remaining is 14 min > 30s TTL
        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVED_AT
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantClaimsPreparationResult.Prepared.class
                );

        ExecutionGrantClaims claims =
                ((ExecutionGrantClaimsPreparationResult.Prepared) result)
                        .claims();

        assertThat(claims.issuedAt())
                .isEqualTo(APPROVED_AT);
        assertThat(claims.expiresAt())
                .isEqualTo(
                        APPROVED_AT.plus(GRANT_TTL)
                );
        assertThat(generator.invocationCount())
                .isEqualTo(1);
    }

    @Test
    void requireApprovalWithValidApprovalWhereRemainingLifetimeShorterThanTtlCapsExpiryToApprovalExpiry() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        // Approval expires at APPROVAL_EXPIRES_AT.
        // If evaluated 10 seconds before APPROVAL_EXPIRES_AT, remaining lifetime is 10s < 30s TTL.
        Instant nearExpiryNow =
                APPROVAL_EXPIRES_AT.minusSeconds(10);

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                approvedApproval()
                        ),
                        nearExpiryNow
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantClaimsPreparationResult.Prepared.class
                );

        ExecutionGrantClaims claims =
                ((ExecutionGrantClaimsPreparationResult.Prepared) result)
                        .claims();

        assertThat(claims.issuedAt())
                .isEqualTo(nearExpiryNow);
        assertThat(claims.expiresAt())
                .isEqualTo(APPROVAL_EXPIRES_AT);
        assertThat(claims.expiresAt())
                .isBefore(
                        nearExpiryNow.plus(GRANT_TTL)
                );
    }

    @Test
    void requireApprovalExpiryCapIsExactWhenPolicyExpiryEqualsApprovalExpiry() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        // Exactly 30 seconds before approval expiry -> policyExpiry == approvalExpiry
        Instant exactNow =
                APPROVAL_EXPIRES_AT.minus(GRANT_TTL);

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                approvedApproval()
                        ),
                        exactNow
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantClaimsPreparationResult.Prepared.class
                );

        ExecutionGrantClaims claims =
                ((ExecutionGrantClaimsPreparationResult.Prepared) result)
                        .claims();

        assertThat(claims.expiresAt())
                .isEqualTo(APPROVAL_EXPIRES_AT);
    }

    @Test
    void requireApprovalAtApprovalExpiryIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVAL_EXPIRES_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void denyIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                DecisionOutcome.DENY
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.DECISION_DENIED
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void failedClosedRuntimeResultIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        new RuntimeGovernanceResult.FailedClosed(
                                ORGANIZATION_ID,
                                AGENT_ID,
                                ACTION_ID,
                                RuntimeGovernanceFailureReason.GOVERNANCE_DECISION_CONFLICT
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.FAILED_CLOSED
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void pendingApprovalIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                pendingApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void rejectedApprovalIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                rejectedApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void expiredApprovalStateIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                expiredApproval()
                        ),
                        APPROVAL_EXPIRES_AT.plusSeconds(1)
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void historicalApprovedAfterExpiryIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVAL_EXPIRES_AT.plusSeconds(1)
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void actionProvenanceMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedActionWithOrganizationId(
                                UUID.randomUUID()
                        ),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.ACTION_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void approvalToolMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedActionWithToolName(
                                new ToolName("github")
                        ),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void approvalOperationMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedActionWithOperationName(
                                new OperationName("create_issue")
                        ),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void approvalPayloadHashMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        governedActionWithPayloadHash(
                                new RequestPayloadHash("b".repeat(64))
                        ),
                        governedResult(
                                approvedApproval()
                        ),
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void twoSuccessfulPreparationsPreserveDistinctGrantIds() {
        ExecutionGrantId id1 =
                new ExecutionGrantId(
                        UUID.fromString(
                                "8a000000-0000-0000-0000-000000000001"
                        )
                );
        ExecutionGrantId id2 =
                new ExecutionGrantId(
                        UUID.fromString(
                                "8a000000-0000-0000-0000-000000000002"
                        )
                );

        Queue<ExecutionGrantId> ids =
                new ArrayDeque<>(
                        List.of(id1, id2)
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        ids::remove,
                        POLICY
                );

        ExecutionGrantClaimsPreparationResult result1 =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                );

        ExecutionGrantClaimsPreparationResult result2 =
                preparer.prepareClaims(
                        governedAction(),
                        governedResult(
                                DecisionOutcome.ALLOW
                        ),
                        APPROVED_AT
                );

        assertThat(((ExecutionGrantClaimsPreparationResult.Prepared) result1).claims().grantId())
                .isEqualTo(id1);
        assertThat(((ExecutionGrantClaimsPreparationResult.Prepared) result2).claims().grantId())
                .isEqualTo(id2);
    }

    @Test
    void rejectsNullConstructorArguments() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        assertThatThrownBy(
                () -> new ExecutionGrantClaimsPreparer(
                        null,
                        generator,
                        POLICY
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("eligibilityEvaluator must not be null");

        assertThatThrownBy(
                () -> new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        null,
                        POLICY
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("grantIdGenerator must not be null");

        assertThatThrownBy(
                () -> new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("issuancePolicy must not be null");
    }

    @Test
    void rejectsNullMethodArguments() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        assertThatThrownBy(
                () -> preparer.prepareClaims(
                        null,
                        governedResult(DecisionOutcome.ALLOW),
                        APPROVED_AT
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("governedAction must not be null");

        assertThatThrownBy(
                () -> preparer.prepareClaims(
                        governedAction(),
                        null,
                        APPROVED_AT
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("runtimeResult must not be null");

        assertThatThrownBy(
                () -> preparer.prepareClaims(
                        governedAction(),
                        governedResult(DecisionOutcome.ALLOW),
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("now must not be null");
    }

    @Test
    void contextExactAllowPreparesClaimsWithExactAuthoritativeProvenance() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.ALLOW
                        ),
                        Optional.empty()
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantClaimsPreparationResult.Prepared.class
                );

        ExecutionGrantClaimsPreparationResult.Prepared prepared =
                (ExecutionGrantClaimsPreparationResult.Prepared)
                        result;

        ExecutionGrantClaims claims =
                prepared.claims();

        assertThat(claims.grantId())
                .isEqualTo(DEFAULT_GRANT_ID);
        assertThat(claims.organizationId())
                .isEqualTo(ORGANIZATION_ID);
        assertThat(claims.agentId())
                .isEqualTo(AGENT_ID);
        assertThat(claims.governedActionId())
                .isEqualTo(ACTION_ID);
        assertThat(claims.governanceDecisionId())
                .isEqualTo(DECISION_ID);
        assertThat(claims.toolName())
                .isEqualTo(TOOL_NAME);
        assertThat(claims.operationName())
                .isEqualTo(OPERATION_NAME);
        assertThat(claims.requestPayloadHash())
                .isEqualTo(REQUEST_PAYLOAD_HASH);
        assertThat(claims.issuer())
                .isEqualTo(ISSUER);
        assertThat(claims.audience())
                .isEqualTo(AUDIENCE);
        assertThat(claims.issuedAt())
                .isEqualTo(APPROVED_AT);
        assertThat(claims.expiresAt())
                .isEqualTo(
                        APPROVED_AT.plus(GRANT_TTL)
                );

        assertThat(generator.invocationCount())
                .isEqualTo(1);
    }

    @Test
    void contextRequireApprovalWithValidApprovalPreparesClaims() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                approvedApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantClaimsPreparationResult.Prepared.class
                );

        ExecutionGrantClaims claims =
                ((ExecutionGrantClaimsPreparationResult.Prepared) result)
                        .claims();

        assertThat(claims.issuedAt())
                .isEqualTo(APPROVED_AT);
        assertThat(claims.expiresAt())
                .isEqualTo(
                        APPROVED_AT.plus(GRANT_TTL)
                );
        assertThat(generator.invocationCount())
                .isEqualTo(1);
    }

    @Test
    void contextRequireApprovalWithValidApprovalCapsExpiryToApprovalExpiry() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        Instant nearExpiryNow =
                APPROVAL_EXPIRES_AT.minusSeconds(10);

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                approvedApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        nearExpiryNow
                );

        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantClaimsPreparationResult.Prepared.class
                );

        ExecutionGrantClaims claims =
                ((ExecutionGrantClaimsPreparationResult.Prepared) result)
                        .claims();

        assertThat(claims.issuedAt())
                .isEqualTo(nearExpiryNow);
        assertThat(claims.expiresAt())
                .isEqualTo(APPROVAL_EXPIRES_AT);
        assertThat(generator.invocationCount())
                .isEqualTo(1);
    }

    @Test
    void contextDenyIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.DENY
                        ),
                        Optional.empty()
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.DECISION_DENIED
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextMissingApprovalIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.empty()
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextPendingApprovalIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                pendingApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextRejectedApprovalIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                rejectedApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextExpiredApprovalStateIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                expiredApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVAL_EXPIRES_AT.plusSeconds(1)
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextHistoricalApprovedApprovalAtExpiresAtIsIneligibleAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                approvedApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVAL_EXPIRES_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_NOT_CURRENTLY_VALID
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextActionOrganizationMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedActionWithOrganizationId(
                                UUID.randomUUID()
                        ),
                        governanceDecision(
                                DecisionOutcome.ALLOW
                        ),
                        Optional.empty()
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.ACTION_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextActionIdMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedActionWithId(
                                UUID.randomUUID()
                        ),
                        governanceDecision(
                                DecisionOutcome.ALLOW
                        ),
                        Optional.empty()
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.ACTION_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextApprovalToolMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedActionWithToolName(
                                new ToolName("github")
                        ),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                approvedApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextApprovalOperationMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedActionWithOperationName(
                                new OperationName("create_issue")
                        ),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                approvedApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextApprovalPayloadHashMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedActionWithPayloadHash(
                                new RequestPayloadHash("b".repeat(64))
                        ),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                approvedApproval()
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextApprovalAgentMismatchPropagatesIneligibleReasonAndDoesNotGenerateId() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.REQUIRE_APPROVAL
                        ),
                        Optional.of(
                                approvedApprovalWithAgentId(
                                        UUID.randomUUID()
                                )
                        )
                );

        ExecutionGrantClaimsPreparationResult result =
                preparer.prepareClaims(
                        context,
                        APPROVED_AT
                );

        assertIneligible(
                result,
                ExecutionGrantEligibility.Reason.APPROVAL_PROVENANCE_MISMATCH
        );
        assertThat(generator.invocationCount())
                .isEqualTo(0);
    }

    @Test
    void contextTwoSuccessfulPreparationsPreserveDistinctGrantIds() {
        ExecutionGrantId id1 =
                new ExecutionGrantId(
                        UUID.fromString(
                                "8a000000-0000-0000-0000-000000000001"
                        )
                );
        ExecutionGrantId id2 =
                new ExecutionGrantId(
                        UUID.fromString(
                                "8a000000-0000-0000-0000-000000000002"
                        )
                );

        Queue<ExecutionGrantId> ids =
                new ArrayDeque<>(
                        List.of(id1, id2)
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        ids::remove,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context1 =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.ALLOW
                        ),
                        Optional.empty()
                );

        ExecutionGrantAuthorizationContext context2 =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.ALLOW
                        ),
                        Optional.empty()
                );

        ExecutionGrantClaimsPreparationResult result1 =
                preparer.prepareClaims(
                        context1,
                        APPROVED_AT
                );

        ExecutionGrantClaimsPreparationResult result2 =
                preparer.prepareClaims(
                        context2,
                        APPROVED_AT
                );

        assertThat(((ExecutionGrantClaimsPreparationResult.Prepared) result1).claims().grantId())
                .isEqualTo(id1);
        assertThat(((ExecutionGrantClaimsPreparationResult.Prepared) result2).claims().grantId())
                .isEqualTo(id2);
    }

    @Test
    void contextRejectsNullMethodArguments() {
        CountingGrantIdGenerator generator =
                new CountingGrantIdGenerator(
                        DEFAULT_GRANT_ID
                );

        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        generator,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.ALLOW
                        ),
                        Optional.empty()
                );

        assertThatThrownBy(
                () -> preparer.prepareClaims(
                        (ExecutionGrantAuthorizationContext) null,
                        APPROVED_AT
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("context must not be null");

        assertThatThrownBy(
                () -> preparer.prepareClaims(
                        context,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("now must not be null");
    }

    @Test
    void contextRejectsNullGrantIdFromGenerator() {
        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        () -> null,
                        POLICY
                );

        ExecutionGrantAuthorizationContext context =
                new ExecutionGrantAuthorizationContext(
                        governedAction(),
                        governanceDecision(
                                DecisionOutcome.ALLOW
                        ),
                        Optional.empty()
                );

        assertThatThrownBy(
                () -> preparer.prepareClaims(
                        context,
                        APPROVED_AT
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("grantId must not be null");
    }

    @Test
    void rejectsNullGrantIdFromGenerator() {
        ExecutionGrantClaimsPreparer preparer =
                new ExecutionGrantClaimsPreparer(
                        eligibilityEvaluator,
                        () -> null,
                        POLICY
                );

        assertThatThrownBy(
                () -> preparer.prepareClaims(
                        governedAction(),
                        governedResult(DecisionOutcome.ALLOW),
                        APPROVED_AT
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("grantId must not be null");
    }

    @Test
    void preparedRejectsNullClaims() {
        assertThatThrownBy(
                () -> new ExecutionGrantClaimsPreparationResult.Prepared(
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("claims must not be null");
    }

    @Test
    void ineligibleRejectsNullReason() {
        assertThatThrownBy(
                () -> new ExecutionGrantClaimsPreparationResult.Ineligible(
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reason must not be null");
    }

    private static void assertIneligible(
            ExecutionGrantClaimsPreparationResult result,
            ExecutionGrantEligibility.Reason reason
    ) {
        assertThat(result)
                .isInstanceOf(
                        ExecutionGrantClaimsPreparationResult.Ineligible.class
                );

        ExecutionGrantClaimsPreparationResult.Ineligible ineligible =
                (ExecutionGrantClaimsPreparationResult.Ineligible)
                        result;

        assertThat(ineligible.reason())
                .isEqualTo(reason);
    }

    private static RuntimeGovernanceResult.Governed governedResult(
            DecisionOutcome outcome
    ) {
        return new RuntimeGovernanceResult.Governed(
                policyBinding(),
                riskAssessment(),
                governanceDecision(outcome)
        );
    }

    private static RuntimeGovernanceResult.Governed governedResult(
            ApprovalRequest approvalRequest
    ) {
        return new RuntimeGovernanceResult.Governed(
                policyBinding(),
                riskAssessment(),
                governanceDecision(DecisionOutcome.REQUIRE_APPROVAL),
                Optional.of(approvalRequest)
        );
    }

    private static GovernedAction governedAction() {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                ACTION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithOrganizationId(
            UUID organizationId
    ) {
        return governedAction(
                organizationId,
                AGENT_ID,
                ACTION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithToolName(
            ToolName toolName
    ) {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                ACTION_ID,
                toolName,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithOperationName(
            OperationName operationName
    ) {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                ACTION_ID,
                TOOL_NAME,
                operationName,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static GovernedAction governedActionWithPayloadHash(
            RequestPayloadHash requestPayloadHash
    ) {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                ACTION_ID,
                TOOL_NAME,
                OPERATION_NAME,
                requestPayloadHash
        );
    }

    private static GovernedAction governedAction(
            UUID organizationId,
            UUID agentId,
            UUID actionId,
            ToolName toolName,
            OperationName operationName,
            RequestPayloadHash requestPayloadHash
    ) {
        return new GovernedAction(
                actionId,
                organizationId,
                agentId,
                new IdempotencyKey("preparer-test-key"),
                toolName,
                operationName,
                new CanonicalRequestPayload(
                        "{\"amount\":100}",
                        requestPayloadHash
                ),
                EVALUATED_AT
        );
    }

    private static AgentPolicyBinding policyBinding() {
        return new AgentPolicyBinding(
                POLICY_BINDING_ID,
                ORGANIZATION_ID,
                AGENT_ID,
                POLICY_VERSION_ID,
                EVALUATED_AT.minusSeconds(60),
                null,
                EVALUATED_AT.minusSeconds(60)
        );
    }

    private static RiskAssessment riskAssessment() {
        return new RiskAssessment(
                RISK_ASSESSMENT_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                new RiskLogicVersion("deterministic-v1"),
                new RiskScore(90),
                List.of(
                        new RiskSignal(
                                new RiskSignalCode("HIGH_RISK_OPERATION"),
                                RiskSeverity.HIGH,
                                90,
                                "Operation carries deterministic high runtime risk."
                        )
                ),
                EVALUATED_AT
        );
    }

    private static GovernanceDecision governanceDecision(
            DecisionOutcome outcome
    ) {
        return new GovernanceDecision(
                DECISION_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                POLICY_VERSION_ID,
                outcome == DecisionOutcome.DENY ? null : POLICY_RULE_ID,
                outcome,
                new RiskScore(90),
                outcome == DecisionOutcome.REQUIRE_APPROVAL
                        ? List.of(new DecisionReasonCode("HIGH_RISK_REFUND"))
                        : outcome == DecisionOutcome.ALLOW
                                ? List.of(new DecisionReasonCode("LOW_RISK_OPERATION"))
                                : List.of(new DecisionReasonCode("NO_APPLICABLE_POLICY_RULE")),
                EVALUATED_AT
        );
    }

    private static ApprovalRequest pendingApproval() {
        return approvalRequest(
                new ApprovalState.Pending()
        );
    }

    private static ApprovalRequest approvedApproval() {
        return pendingApproval()
                .approve(
                        new ApprovalActorId("operator-subject-001"),
                        new ApprovalRationale("Authorized for exact retry."),
                        APPROVED_AT
                );
    }

    private static ApprovalRequest rejectedApproval() {
        return pendingApproval()
                .reject(
                        new ApprovalActorId("operator-subject-001"),
                        new ApprovalRationale("Rejected by operator."),
                        APPROVED_AT
                );
    }

    private static ApprovalRequest expiredApproval() {
        return pendingApproval()
                .expire(
                        APPROVAL_EXPIRES_AT
                );
    }

    private static ApprovalRequest approvalRequest(
            ApprovalState state
    ) {
        return new ApprovalRequest(
                APPROVAL_REQUEST_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                DECISION_ID,
                AGENT_ID,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                EVALUATED_AT,
                APPROVAL_EXPIRES_AT,
                state
        );
    }

    private static GovernedAction governedActionWithId(
            UUID actionId
    ) {
        return governedAction(
                ORGANIZATION_ID,
                AGENT_ID,
                actionId,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH
        );
    }

    private static ApprovalRequest approvedApprovalWithAgentId(
            UUID agentId
    ) {
        return new ApprovalRequest(
                APPROVAL_REQUEST_ID,
                ORGANIZATION_ID,
                ACTION_ID,
                DECISION_ID,
                agentId,
                TOOL_NAME,
                OPERATION_NAME,
                REQUEST_PAYLOAD_HASH,
                EVALUATED_AT,
                APPROVAL_EXPIRES_AT,
                new ApprovalState.Approved(
                        new ApprovalActorId("operator-subject-001"),
                        new ApprovalRationale("Authorized for exact retry."),
                        APPROVED_AT
                )
        );
    }

    private static final class CountingGrantIdGenerator
            implements ExecutionGrantIdGenerator {

        private final ExecutionGrantId grantId;
        private final AtomicInteger invocations =
                new AtomicInteger(0);

        CountingGrantIdGenerator(
                ExecutionGrantId grantId
        ) {
            this.grantId = grantId;
        }

        @Override
        public ExecutionGrantId nextGrantId() {
            invocations.incrementAndGet();
            return grantId;
        }

        int invocationCount() {
            return invocations.get();
        }
    }
}
