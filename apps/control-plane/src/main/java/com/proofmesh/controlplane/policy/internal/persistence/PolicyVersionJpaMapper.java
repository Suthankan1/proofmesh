package com.proofmesh.controlplane.policy.internal.persistence;

import com.proofmesh.controlplane.policy.CanonicalPolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionCanonicalizer;
import com.proofmesh.controlplane.policy.PolicyDefinitionHash;
import com.proofmesh.controlplane.policy.PolicyDefinitionParser;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.policy.PolicyVersionIntegrityException;
import com.proofmesh.controlplane.policy.PolicyVersionNumber;
import com.proofmesh.controlplane.policy.PolicyVersionState;

import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class PolicyVersionJpaMapper {

    private final PolicyDefinitionParser
            policyDefinitionParser;

    private final PolicyDefinitionCanonicalizer
            policyDefinitionCanonicalizer;

    PolicyVersionJpaMapper(
            PolicyDefinitionParser
                    policyDefinitionParser,
            PolicyDefinitionCanonicalizer
                    policyDefinitionCanonicalizer
    ) {
        this.policyDefinitionParser =
                Objects.requireNonNull(
                        policyDefinitionParser,
                        "policyDefinitionParser must not be null"
                );

        this.policyDefinitionCanonicalizer =
                Objects.requireNonNull(
                        policyDefinitionCanonicalizer,
                        "policyDefinitionCanonicalizer must not be null"
                );
    }

    PolicyVersion toDomain(
            PolicyVersionJpaEntity entity
    ) {
        Objects.requireNonNull(
                entity,
                "entity must not be null"
        );

        try {
            if (entity.definition() == null) {
                throw new PolicyVersionIntegrityException(
                        "stored policy definition must not be null"
                );
            }

            PolicyDefinition definition =
                    policyDefinitionParser.parse(
                            entity.definition()
                                    .toString()
                    );

            PolicyDefinitionHash definitionHash =
                    definitionHash(
                            entity
                    );

            verifyPublishedDefinitionIntegrity(
                    entity,
                    definition,
                    definitionHash
            );

            return new PolicyVersion(
                    new PolicyVersionId(
                            entity.id()
                    ),
                    entity.policyId(),
                    entity.organizationId(),
                    new PolicyVersionNumber(
                            entity.versionNumber()
                    ),
                    entity.state(),
                    definition,
                    definitionHash,
                    entity.createdAt(),
                    entity.publishedAt()
            );
        } catch (PolicyVersionIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PolicyVersionIntegrityException(
                    "stored policy version failed integrity validation",
                    exception
            );
        }
    }

    private PolicyDefinitionHash definitionHash(
            PolicyVersionJpaEntity entity
    ) {
        if (entity.definitionHash() == null) {
            return null;
        }

        return new PolicyDefinitionHash(
                entity.definitionHash()
        );
    }

    private void verifyPublishedDefinitionIntegrity(
            PolicyVersionJpaEntity entity,
            PolicyDefinition definition,
            PolicyDefinitionHash storedHash
    ) {
        if (entity.state()
                != PolicyVersionState.PUBLISHED) {
            return;
        }

        if (storedHash == null) {
            throw new PolicyVersionIntegrityException(
                    "published policy version is missing its definition hash"
            );
        }

        CanonicalPolicyDefinition canonical =
                policyDefinitionCanonicalizer
                        .canonicalize(
                                definition
                        );

        if (!canonical.hash()
                .equals(
                        storedHash
                )) {
            throw new PolicyVersionIntegrityException(
                    "published policy definition hash does not match persisted definition"
            );
        }
    }
}