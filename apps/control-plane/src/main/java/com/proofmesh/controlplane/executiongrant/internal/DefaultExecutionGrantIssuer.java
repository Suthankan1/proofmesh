package com.proofmesh.controlplane.executiongrant.internal;

import com.proofmesh.controlplane.executiongrant.ExecutionGrantAuthorizationContext;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaims;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaimsPreparationResult;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaimsPreparer;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuanceResult;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuer;
import com.proofmesh.controlplane.executiongrant.SignedExecutionGrant;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class DefaultExecutionGrantIssuer implements ExecutionGrantIssuer {

    private final ExecutionGrantAuthorizationContextResolver contextResolver;
    private final ExecutionGrantClaimsPreparer claimsPreparer;
    private final ExecutionGrantSigningKeyProvider signingKeyProvider;
    private final ExecutionGrantSigner signer;
    private final Clock clock;

    DefaultExecutionGrantIssuer(
            ExecutionGrantAuthorizationContextResolver contextResolver,
            ExecutionGrantClaimsPreparer claimsPreparer,
            ExecutionGrantSigningKeyProvider signingKeyProvider,
            ExecutionGrantSigner signer,
            Clock clock
    ) {
        this.contextResolver = Objects.requireNonNull(
                contextResolver,
                "contextResolver must not be null"
        );
        this.claimsPreparer = Objects.requireNonNull(
                claimsPreparer,
                "claimsPreparer must not be null"
        );
        this.signingKeyProvider = Objects.requireNonNull(
                signingKeyProvider,
                "signingKeyProvider must not be null"
        );
        this.signer = Objects.requireNonNull(
                signer,
                "signer must not be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock must not be null"
        );
    }

    @Override
    public ExecutionGrantIssuanceResult issue(
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

        Optional<ExecutionGrantAuthorizationContext> contextOpt =
                contextResolver.resolve(organizationId, governedActionId);
        if (contextOpt.isEmpty()) {
            return new ExecutionGrantIssuanceResult.StateUnavailable(
                    organizationId,
                    governedActionId
            );
        }

        ExecutionGrantAuthorizationContext context = contextOpt.get();

        Instant authorizationNow = clock.instant();
        Objects.requireNonNull(
                authorizationNow,
                "authorizationNow from clock must not be null"
        );

        ExecutionGrantClaimsPreparationResult preparationResult =
                claimsPreparer.prepareClaims(context, authorizationNow);

        if (preparationResult instanceof ExecutionGrantClaimsPreparationResult.Ineligible ineligible) {
            return new ExecutionGrantIssuanceResult.Ineligible(
                    ineligible.reason()
            );
        }

        if (!(preparationResult instanceof ExecutionGrantClaimsPreparationResult.Prepared prepared)) {
            throw new IllegalStateException(
                    "Unexpected claims preparation result: " + preparationResult
            );
        }

        ExecutionGrantClaims claims = prepared.claims();

        ExecutionGrantSigningKey signingKey = signingKeyProvider.activeSigningKey();
        Objects.requireNonNull(
                signingKey,
                "activeSigningKey must not be null"
        );

        Instant signingNow = clock.instant();
        Objects.requireNonNull(
                signingNow,
                "signingNow from clock must not be null"
        );

        Instant effectiveExpiration = Instant.ofEpochSecond(
                claims.expiresAt().getEpochSecond()
        );

        if (!signingNow.isBefore(effectiveExpiration)) {
            return new ExecutionGrantIssuanceResult.StaleAuthorization(
                    signingNow,
                    effectiveExpiration
            );
        }

        SignedExecutionGrant signedGrant = signer.sign(claims, signingKey);
        Objects.requireNonNull(
                signedGrant,
                "signedGrant must not be null"
        );

        return new ExecutionGrantIssuanceResult.Issued(signedGrant);
    }
}
