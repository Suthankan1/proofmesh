package com.proofmesh.controlplane.executiongrant;

import com.proofmesh.controlplane.approval.ApprovalRequest;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import com.proofmesh.controlplane.runtimegovernance.RuntimeGovernanceResult;

import java.time.Instant;
import java.util.Objects;

public final class ExecutionGrantClaimsPreparer {

    private final ExecutionGrantEligibilityEvaluator
            eligibilityEvaluator;

    private final ExecutionGrantIdGenerator
            grantIdGenerator;

    private final ExecutionGrantIssuancePolicy
            issuancePolicy;

    public ExecutionGrantClaimsPreparer(
            ExecutionGrantEligibilityEvaluator eligibilityEvaluator,
            ExecutionGrantIdGenerator grantIdGenerator,
            ExecutionGrantIssuancePolicy issuancePolicy
    ) {
        this.eligibilityEvaluator =
                Objects.requireNonNull(
                        eligibilityEvaluator,
                        "eligibilityEvaluator must not be null"
                );

        this.grantIdGenerator =
                Objects.requireNonNull(
                        grantIdGenerator,
                        "grantIdGenerator must not be null"
                );

        this.issuancePolicy =
                Objects.requireNonNull(
                        issuancePolicy,
                        "issuancePolicy must not be null"
                );
    }

    public ExecutionGrantClaimsPreparationResult prepareClaims(
            GovernedAction governedAction,
            RuntimeGovernanceResult runtimeResult,
            Instant now
    ) {
        Objects.requireNonNull(
                governedAction,
                "governedAction must not be null"
        );

        Objects.requireNonNull(
                runtimeResult,
                "runtimeResult must not be null"
        );

        Objects.requireNonNull(
                now,
                "now must not be null"
        );

        ExecutionGrantEligibility eligibility =
                eligibilityEvaluator.evaluate(
                        governedAction,
                        runtimeResult,
                        now
                );

        if (eligibility instanceof ExecutionGrantEligibility.Ineligible ineligible) {
            return new ExecutionGrantClaimsPreparationResult.Ineligible(
                    ineligible.reason()
            );
        }

        if (!(runtimeResult instanceof RuntimeGovernanceResult.Governed governed)) {
            throw new IllegalStateException(
                    "Eligible outcome requires a Governed runtime result"
            );
        }

        Instant issuedAt = now;
        Instant policyExpiresAt =
                issuedAt.plus(
                        issuancePolicy.grantTtl()
                );

        Instant expiresAt = policyExpiresAt;

        if (governed.decision().requiresApproval()) {
            ApprovalRequest approvalRequest =
                    governed.approvalRequest()
                            .orElseThrow(
                                    () -> new IllegalStateException(
                                            "REQUIRE_APPROVAL governance result must have an approval request"
                                    )
                            );

            if (approvalRequest.expiresAt()
                    .isBefore(policyExpiresAt)) {
                expiresAt = approvalRequest.expiresAt();
            }
        }

        ExecutionGrantId grantId =
                grantIdGenerator.nextGrantId();

        Objects.requireNonNull(
                grantId,
                "grantId must not be null"
        );

        ExecutionGrantClaims claims =
                new ExecutionGrantClaims(
                        grantId,
                        governedAction.organizationId(),
                        governedAction.agentId(),
                        governedAction.id(),
                        governed.decision().id(),
                        governedAction.toolName(),
                        governedAction.operationName(),
                        governedAction.requestPayloadHash(),
                        issuancePolicy.issuer(),
                        issuancePolicy.audience(),
                        issuedAt,
                        expiresAt
                );

        return new ExecutionGrantClaimsPreparationResult.Prepared(
                claims
        );
    }
}
