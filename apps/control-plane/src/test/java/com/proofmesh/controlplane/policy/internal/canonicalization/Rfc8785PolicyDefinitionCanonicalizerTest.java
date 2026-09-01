package com.proofmesh.controlplane.policy.internal.canonicalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.proofmesh.controlplane.policy.CanonicalPolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionCanonicalizer;
import com.proofmesh.controlplane.policy.PolicyEffect;
import com.proofmesh.controlplane.policy.PolicyReasonCode;
import com.proofmesh.controlplane.policy.PolicyRiskThreshold;
import com.proofmesh.controlplane.policy.PolicyRule;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyRulePriority;
import com.proofmesh.controlplane.policy.PolicyTarget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

class Rfc8785PolicyDefinitionCanonicalizerTest {

    private PolicyDefinitionCanonicalizer canonicalizer;

    @BeforeEach
    void setUp() {
        canonicalizer =
                new Rfc8785PolicyDefinitionCanonicalizer(
                        JsonMapper.builder()
                                .build()
                );
    }

    @Test
    void canonicalizesPolicyDefinitionDeterministically() {
        PolicyDefinition definition =
                new PolicyDefinition(
                        List.of(
                                rule(
                                        "51000000-0000-0000-0000-000000000001",
                                        100,
                                        80,
                                        PolicyEffect.REQUIRE_APPROVAL,
                                        "HIGH_RISK_REFUND"
                                ),
                                rule(
                                        "51000000-0000-0000-0000-000000000002",
                                        200,
                                        0,
                                        PolicyEffect.ALLOW,
                                        "STANDARD_REFUND"
                                )
                        )
                );

        CanonicalPolicyDefinition canonical =
                canonicalizer.canonicalize(
                        definition
                );

        assertThat(
                canonical.canonicalJson()
        ).isEqualTo(
                """
                {"rules":[{"effect":"REQUIRE_APPROVAL","id":"51000000-0000-0000-0000-000000000001","priority":100,"reasonCode":"HIGH_RISK_REFUND","risk":{"minimum":80},"target":{"operation":"refund_payment","tool":"stripe"}},{"effect":"ALLOW","id":"51000000-0000-0000-0000-000000000002","priority":200,"reasonCode":"STANDARD_REFUND","risk":{"minimum":0},"target":{"operation":"refund_payment","tool":"stripe"}}]}"""
        );

        assertThat(
                canonical.hash().value()
        ).isEqualTo(
                "46f7bc30cea7ca4977c46b4cfb5399a3ecd53bbe73b19f499f17f3b6ae578bd4"
        );
    }

    @Test
    void inputRuleOrderDoesNotChangeCanonicalIdentity() {
        PolicyRule highRisk =
                rule(
                        "51000000-0000-0000-0000-000000000001",
                        100,
                        80,
                        PolicyEffect.REQUIRE_APPROVAL,
                        "HIGH_RISK_REFUND"
                );

        PolicyRule ordinary =
                rule(
                        "51000000-0000-0000-0000-000000000002",
                        200,
                        0,
                        PolicyEffect.ALLOW,
                        "STANDARD_REFUND"
                );

        PolicyDefinition first =
                new PolicyDefinition(
                        List.of(
                                highRisk,
                                ordinary
                        )
                );

        PolicyDefinition reversed =
                new PolicyDefinition(
                        List.of(
                                ordinary,
                                highRisk
                        )
                );

        CanonicalPolicyDefinition firstCanonical =
                canonicalizer.canonicalize(
                        first
                );

        CanonicalPolicyDefinition reversedCanonical =
                canonicalizer.canonicalize(
                        reversed
                );

        assertThat(
                reversedCanonical
        ).isEqualTo(
                firstCanonical
        );
    }

    @Test
    void policySemanticChangeProducesDifferentHash() {
        PolicyDefinition first =
                new PolicyDefinition(
                        List.of(
                                rule(
                                        "51000000-0000-0000-0000-000000000003",
                                        100,
                                        80,
                                        PolicyEffect.REQUIRE_APPROVAL,
                                        "HIGH_RISK_REFUND"
                                )
                        )
                );

        PolicyDefinition changed =
                new PolicyDefinition(
                        List.of(
                                rule(
                                        "51000000-0000-0000-0000-000000000003",
                                        100,
                                        90,
                                        PolicyEffect.REQUIRE_APPROVAL,
                                        "HIGH_RISK_REFUND"
                                )
                        )
                );

        CanonicalPolicyDefinition firstCanonical =
                canonicalizer.canonicalize(
                        first
                );

        CanonicalPolicyDefinition changedCanonical =
                canonicalizer.canonicalize(
                        changed
                );

        assertThat(
                changedCanonical.hash()
        ).isNotEqualTo(
                firstCanonical.hash()
        );
    }

    @Test
    void emptyPolicyHasStableCanonicalIdentity() {
        PolicyDefinition definition =
                new PolicyDefinition(
                        List.of()
                );

        CanonicalPolicyDefinition canonical =
                canonicalizer.canonicalize(
                        definition
                );

        assertThat(
                canonical.canonicalJson()
        ).isEqualTo(
                "{\"rules\":[]}"
        );

        assertThat(
                canonical.hash().value()
        ).isEqualTo(
                "da506c8a9c8a9f31aa00eaeef23d49764b9ace97158a1a0a7aa628e6d446b0fb"
        );
    }

    private PolicyRule rule(
            String id,
            int priority,
            int minimumRisk,
            PolicyEffect effect,
            String reasonCode
    ) {
        return new PolicyRule(
                new PolicyRuleId(
                        UUID.fromString(
                                id
                        )
                ),
                new PolicyRulePriority(
                        priority
                ),
                new PolicyTarget(
                        "stripe",
                        "refund_payment"
                ),
                new PolicyRiskThreshold(
                        minimumRisk
                ),
                effect,
                new PolicyReasonCode(
                        reasonCode
                )
        );
    }
}