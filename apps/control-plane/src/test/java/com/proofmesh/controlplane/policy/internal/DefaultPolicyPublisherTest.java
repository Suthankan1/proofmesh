package com.proofmesh.controlplane.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.proofmesh.controlplane.policy.CanonicalPolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionCanonicalizer;
import com.proofmesh.controlplane.policy.PolicyDefinitionHash;
import com.proofmesh.controlplane.policy.PolicyPublisher;
import com.proofmesh.controlplane.policy.PolicyVersion;
import com.proofmesh.controlplane.policy.PolicyVersionId;
import com.proofmesh.controlplane.policy.PolicyVersionNumber;
import com.proofmesh.controlplane.policy.PolicyVersionState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class DefaultPolicyPublisherTest {

    private static final PolicyVersionId VERSION_ID =
            new PolicyVersionId(
                    UUID.fromString(
                            "61000000-0000-0000-0000-000000000001"
                    )
            );

    private static final UUID POLICY_ID =
            UUID.fromString(
                    "62000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "63000000-0000-0000-0000-000000000001"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-31T10:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-31T10:30:00Z"
            );

    private static final PolicyDefinitionHash
            DEFINITION_HASH =
            new PolicyDefinitionHash(
                    "a".repeat(64)
            );

    private PolicyDefinitionCanonicalizer
            canonicalizer;

    private PolicyPublisher publisher;

    @BeforeEach
    void setUp() {
        canonicalizer =
                mock(
                        PolicyDefinitionCanonicalizer.class
                );

        publisher =
                new DefaultPolicyPublisher(
                        canonicalizer
                );
    }

    @Test
    void publishesDraftWithCanonicalDefinitionHash() {
        PolicyVersion draft =
                draftVersion();

        CanonicalPolicyDefinition canonical =
                new CanonicalPolicyDefinition(
                        "{\"rules\":[]}",
                        DEFINITION_HASH
                );

        when(
                canonicalizer.canonicalize(
                        draft.definition()
                )
        ).thenReturn(
                canonical
        );

        PolicyVersion published =
                publisher.publish(
                        draft,
                        PUBLISHED_AT
                );

        assertThat(published.id())
                .isEqualTo(
                        draft.id()
                );

        assertThat(published.policyId())
                .isEqualTo(
                        draft.policyId()
                );

        assertThat(published.organizationId())
                .isEqualTo(
                        draft.organizationId()
                );

        assertThat(published.versionNumber())
                .isEqualTo(
                        draft.versionNumber()
                );

        assertThat(published.definition())
                .isEqualTo(
                        draft.definition()
                );

        assertThat(published.state())
                .isEqualTo(
                        PolicyVersionState.PUBLISHED
                );

        assertThat(published.definitionHash())
                .isEqualTo(
                        DEFINITION_HASH
                );

        assertThat(published.publishedAt())
                .isEqualTo(
                        PUBLISHED_AT
                );

        verify(
                canonicalizer
        ).canonicalize(
                draft.definition()
        );
    }

    @Test
    void publishingDoesNotMutateDraft() {
        PolicyVersion draft =
                draftVersion();

        when(
                canonicalizer.canonicalize(
                        draft.definition()
                )
        ).thenReturn(
                new CanonicalPolicyDefinition(
                        "{\"rules\":[]}",
                        DEFINITION_HASH
                )
        );

        publisher.publish(
                draft,
                PUBLISHED_AT
        );

        assertThat(draft.state())
                .isEqualTo(
                        PolicyVersionState.DRAFT
                );

        assertThat(draft.definitionHash())
                .isNull();

        assertThat(draft.publishedAt())
                .isNull();
    }

    @Test
    void rejectsAlreadyPublishedVersionBeforeCanonicalization() {
        PolicyVersion published =
                new PolicyVersion(
                        VERSION_ID,
                        POLICY_ID,
                        ORGANIZATION_ID,
                        new PolicyVersionNumber(1),
                        PolicyVersionState.PUBLISHED,
                        definition(),
                        DEFINITION_HASH,
                        CREATED_AT,
                        PUBLISHED_AT
                );

        assertThatThrownBy(
                () -> publisher.publish(
                        published,
                        Instant.parse(
                                "2026-08-31T11:00:00Z"
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );

        verifyNoInteractions(
                canonicalizer
        );
    }

    @Test
    void rejectsPublicationBeforeCreationBeforeCanonicalization() {
        PolicyVersion draft =
                draftVersion();

        assertThatThrownBy(
                () -> publisher.publish(
                        draft,
                        Instant.parse(
                                "2026-08-31T09:59:59Z"
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        verifyNoInteractions(
                canonicalizer
        );
    }

    private PolicyVersion draftVersion() {
        return new PolicyVersion(
                VERSION_ID,
                POLICY_ID,
                ORGANIZATION_ID,
                new PolicyVersionNumber(1),
                PolicyVersionState.DRAFT,
                definition(),
                null,
                CREATED_AT,
                null
        );
    }

    private PolicyDefinition definition() {
        return new PolicyDefinition(
                List.of()
        );
    }
}