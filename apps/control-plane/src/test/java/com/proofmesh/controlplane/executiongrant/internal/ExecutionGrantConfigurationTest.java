package com.proofmesh.controlplane.executiongrant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.proofmesh.controlplane.approval.ApprovalRequestRepository;
import com.proofmesh.controlplane.decision.GovernanceDecisionRepository;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantClaimsPreparer;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantEligibilityEvaluator;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantId;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIdGenerator;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuancePolicy;
import com.proofmesh.controlplane.executiongrant.ExecutionGrantIssuer;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;

class ExecutionGrantConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ExecutionGrantConfiguration.class)
            .withPropertyValues(
                    "proofmesh.execution-grant.issuer=test-control-plane",
                    "proofmesh.execution-grant.audience=test-gateway",
                    "proofmesh.execution-grant.grant-ttl=45s"
            )
            .withBean(GovernedActionRepository.class, () -> mock(GovernedActionRepository.class))
            .withBean(GovernanceDecisionRepository.class, () -> mock(GovernanceDecisionRepository.class))
            .withBean(ApprovalRequestRepository.class, () -> mock(ApprovalRequestRepository.class));

    @Test
    void wiresCoreExecutionGrantBeansSuccessfully() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            // 1. policy properties bind from configured test values
            assertThat(context).hasSingleBean(ExecutionGrantPolicyProperties.class);
            ExecutionGrantPolicyProperties properties =
                    context.getBean(ExecutionGrantPolicyProperties.class);
            assertThat(properties.issuer()).isEqualTo("test-control-plane");
            assertThat(properties.audience()).isEqualTo("test-gateway");
            assertThat(properties.grantTtl()).isEqualTo(Duration.ofSeconds(45));

            // 2. ExecutionGrantIssuancePolicy bean contains exact configured values
            assertThat(context).hasSingleBean(ExecutionGrantIssuancePolicy.class);
            ExecutionGrantIssuancePolicy policy =
                    context.getBean(ExecutionGrantIssuancePolicy.class);
            assertThat(policy.issuer()).isEqualTo("test-control-plane");
            assertThat(policy.audience()).isEqualTo("test-gateway");
            assertThat(policy.grantTtl()).isEqualTo(Duration.ofSeconds(45));

            // 3. one ExecutionGrantEligibilityEvaluator bean exists
            assertThat(context).hasSingleBean(ExecutionGrantEligibilityEvaluator.class);

            // 4. one ExecutionGrantIdGenerator bean exists
            assertThat(context).hasSingleBean(ExecutionGrantIdGenerator.class);
            ExecutionGrantIdGenerator generator =
                    context.getBean(ExecutionGrantIdGenerator.class);
            assertThat(generator).isInstanceOf(RandomExecutionGrantIdGenerator.class);

            // 5. generator produces non-null UUID-backed IDs
            ExecutionGrantId id1 = generator.nextGrantId();
            ExecutionGrantId id2 = generator.nextGrantId();
            assertThat(id1).isNotNull();
            assertThat(id1.value()).isNotNull();
            assertThat(id2).isNotNull();
            assertThat(id2.value()).isNotNull();
            assertThat(id1).isNotEqualTo(id2);

            // 6. one ExecutionGrantClaimsPreparer bean exists
            assertThat(context).hasSingleBean(ExecutionGrantClaimsPreparer.class);

            // 7. one internal ExecutionGrantSigner bean exists and is Nimbus-backed
            assertThat(context).hasSingleBean(ExecutionGrantSigner.class);
            ExecutionGrantSigner signer = context.getBean(ExecutionGrantSigner.class);
            assertThat(signer).isInstanceOf(NimbusExecutionGrantSigner.class);

            // 8. one internal ExecutionGrantAuthorizationContextResolver bean exists and is default implementation
            assertThat(context).hasSingleBean(ExecutionGrantAuthorizationContextResolver.class);
            ExecutionGrantAuthorizationContextResolver resolver =
                    context.getBean(ExecutionGrantAuthorizationContextResolver.class);
            assertThat(resolver).isInstanceOf(DefaultExecutionGrantAuthorizationContextResolver.class);

            // 9. resolver is wired to repository beans/ports without policy/risk/runtime-governance dependencies
            // (verified by contextRunner only supplying action, decision, and approval repository beans)

            // 10. there is NO production ExecutionGrantSigningKeyProvider bean from this configuration
            assertThat(context).doesNotHaveBean(ExecutionGrantSigningKeyProvider.class);

            // 11. there is NO ExecutionGrantIssuer bean from this configuration
            assertThat(context).doesNotHaveBean(ExecutionGrantIssuer.class);

            // 12. no extra Clock bean is declared by executiongrant configuration
            assertThat(context).doesNotHaveBean(Clock.class);
        });
    }

    @Test
    void failsContextInitializationWhenConfigurationPropertiesAreInvalid() {
        new ApplicationContextRunner()
                .withUserConfiguration(ExecutionGrantConfiguration.class)
                .withPropertyValues(
                        "proofmesh.execution-grant.issuer=",
                        "proofmesh.execution-grant.audience=test-gateway",
                        "proofmesh.execution-grant.grant-ttl=45s"
                )
                .withBean(GovernedActionRepository.class, () -> mock(GovernedActionRepository.class))
                .withBean(GovernanceDecisionRepository.class, () -> mock(GovernanceDecisionRepository.class))
                .withBean(ApprovalRequestRepository.class, () -> mock(ApprovalRequestRepository.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsContextInitializationWhenGrantTtlIsZeroOrNegative() {
        new ApplicationContextRunner()
                .withUserConfiguration(ExecutionGrantConfiguration.class)
                .withPropertyValues(
                        "proofmesh.execution-grant.issuer=test-control-plane",
                        "proofmesh.execution-grant.audience=test-gateway",
                        "proofmesh.execution-grant.grant-ttl=0s"
                )
                .withBean(GovernedActionRepository.class, () -> mock(GovernedActionRepository.class))
                .withBean(GovernanceDecisionRepository.class, () -> mock(GovernanceDecisionRepository.class))
                .withBean(ApprovalRequestRepository.class, () -> mock(ApprovalRequestRepository.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void executionGrantIssuerBeanIsExplicitlyAbsent() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ExecutionGrantIssuer.class);
            assertThat(context).doesNotHaveBean(ExecutionGrantSigningKeyProvider.class);
        });
    }
}
