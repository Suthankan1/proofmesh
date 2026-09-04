package com.proofmesh.controlplane.executiongrant.internal;

import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaimsPreparer;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantEligibilityEvaluator;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIdGenerator;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuancePolicy;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuer;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        ExecutionGrantPolicyProperties.class,
        ExecutionGrantSigningProperties.class
})
class ExecutionGrantConfiguration {

    @Bean
    ExecutionGrantEligibilityEvaluator executionGrantEligibilityEvaluator() {
        return new ExecutionGrantEligibilityEvaluator();
    }

    @Bean
    ExecutionGrantIdGenerator executionGrantIdGenerator() {
        return new RandomExecutionGrantIdGenerator();
    }

    @Bean
    ExecutionGrantIssuancePolicy executionGrantIssuancePolicy(
            ExecutionGrantPolicyProperties properties
    ) {
        return new ExecutionGrantIssuancePolicy(
                properties.issuer(),
                properties.audience(),
                properties.grantTtl()
        );
    }

    @Bean
    ExecutionGrantClaimsPreparer executionGrantClaimsPreparer(
            ExecutionGrantEligibilityEvaluator evaluator,
            ExecutionGrantIdGenerator idGenerator,
            ExecutionGrantIssuancePolicy issuancePolicy
    ) {
        return new ExecutionGrantClaimsPreparer(
                evaluator,
                idGenerator,
                issuancePolicy
        );
    }

    @Bean
    ExecutionGrantSigner executionGrantSigner() {
        return new NimbusExecutionGrantSigner();
    }

    @Bean
    ExecutionGrantAuthorizationContextResolver executionGrantAuthorizationContextResolver(
            GovernedActionRepository actionRepository,
            GovernanceDecisionRepository decisionRepository,
            ApprovalRequestRepository approvalRepository
    ) {
        return new DefaultExecutionGrantAuthorizationContextResolver(
                actionRepository,
                decisionRepository,
                approvalRepository
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "proofmesh.execution-grant.signing",
            name = "enabled",
            havingValue = "true"
    )
    ExecutionGrantSigningKeyProvider executionGrantSigningKeyProvider(
            ExecutionGrantSigningProperties properties
    ) {
        return new FileBasedExecutionGrantKeyProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "proofmesh.execution-grant.signing",
            name = "enabled",
            havingValue = "true"
    )
    ExecutionGrantIssuer executionGrantIssuer(
            ExecutionGrantAuthorizationContextResolver contextResolver,
            ExecutionGrantClaimsPreparer claimsPreparer,
            ExecutionGrantSigningKeyProvider signingKeyProvider,
            ExecutionGrantSigner signer,
            Clock clock
    ) {
        return new DefaultExecutionGrantIssuer(
                contextResolver,
                claimsPreparer,
                signingKeyProvider,
                signer,
                clock
        );
    }
}
