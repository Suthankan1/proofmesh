package com.proofmesh.controlplane.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class PolicyVersionTest {

    private static final PolicyVersionId VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "21000000-0000-0000-0000-000000000001"
                    )
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "22000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "23000000-0000-0000-0000-000000000001"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-31T10:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-31T10:30:00Z"
            );

    @Test
    void createsDraftPolicyVersion() {
        PolicyVersion draft =
                draftVersion();

        assertThat(draft.state())
                .isEqualTo(
                        PolicyVersionState.DRAFT
                );

        assertThat(draft.isPublished())
                .isFalse();

        assertThat(draft.definition())
                .isEqualTo(
                        emptyDefinition()
                );

        assertThat(draft.definitionHash())
                .isNull();

        assertThat(draft.publishedAt())
                .isNull();
    }

    @Test
    void createsValidPublishedPolicyVersion() {
        PolicyDefinition definition =
                emptyDefinition();

        PolicyDefinitionHash definitionHash =
                definitionHash();

        PolicyVersion published =
                new PolicyVersion(
                        VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.PUBLISHED,
                        definition,
                        definitionHash,
                        CREATED_AT,
                        PUBLISHED_AT
                );

        assertThat(published.isPublished())
                .isTrue();

        assertThat(published.definition())
                .isEqualTo(
                        definition
                );

        assertThat(published.definitionHash())
                .isEqualTo(
                        definitionHash
                );

        assertThat(published.publishedAt())
                .isEqualTo(
                        PUBLISHED_AT
                );
    }

    @Test
    void rejectsPublishedVersionWithoutPublishedTimestamp() {
        assertThatThrownBy(
                () -> new PolicyVersion(
                        VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.PUBLISHED,
                        emptyDefinition(),
                        definitionHash(),
                        CREATED_AT,
                        null
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsPublishedVersionWithoutDefinitionHash() {
        assertThatThrownBy(
                () -> new PolicyVersion(
                        VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.PUBLISHED,
                        emptyDefinition(),
                        null,
                        CREATED_AT,
                        PUBLISHED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsDraftVersionWithPublishedTimestamp() {
        assertThatThrownBy(
                () -> new PolicyVersion(
                        VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.DRAFT,
                        emptyDefinition(),
                        null,
                        CREATED_AT,
                        PUBLISHED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsDraftVersionWithDefinitionHash() {
        assertThatThrownBy(
                () -> new PolicyVersion(
                        VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.DRAFT,
                        emptyDefinition(),
                        definitionHash(),
                        CREATED_AT,
                        null
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsPublishedTimestampBeforeCreationTime() {
        assertThatThrownBy(
                () -> new PolicyVersion(
                        VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.PUBLISHED,
                        emptyDefinition(),
                        definitionHash(),
                        CREATED_AT,
                        Instant.parse(
                                "2026-08-31T09:59:59Z"
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void rejectsInvalidVersionNumber() {
        assertThatThrownBy(
                () -> new PolicyVersionNumber(0)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void incrementsVersionNumber() {
        PolicyVersionNumber version =
                new PolicyVersionNumber(3);

        assertThat(
                version.next()
        )
                .isEqualTo(
                        new PolicyVersionNumber(4)
                );
    }

    private PolicyVersion draftVersion() {
        return new PolicyVersion(
                VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                new PolicyVersionNumber(1),
                PolicyVersionState.DRAFT,
                emptyDefinition(),
                null,
                CREATED_AT,
                null
        );
    }

    private PolicyDefinition emptyDefinition() {
        return new PolicyDefinition(
                List.of()
        );
    }

    private PolicyDefinitionHash definitionHash() {
        return new PolicyDefinitionHash(
                "a".repeat(64)
        );
    }
}