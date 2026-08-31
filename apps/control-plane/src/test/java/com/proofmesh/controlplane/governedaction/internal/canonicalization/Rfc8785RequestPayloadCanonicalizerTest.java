package com.proofmesh.controlplane.governedaction.internal.canonicalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proofmesh.controlplane.governedaction.CanonicalRequestPayload;
import com.proofmesh.controlplane.governedaction.InvalidRequestPayloadException;
import com.proofmesh.controlplane.governedaction.RequestPayloadCanonicalizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Rfc8785RequestPayloadCanonicalizerTest {

    private RequestPayloadCanonicalizer
            canonicalizer;

    @BeforeEach
    void setUp() {
        canonicalizer =
                new Rfc8785RequestPayloadCanonicalizer();
    }

    @Test
    void canonicalizesObjectAndCalculatesSha256() {
        String input =
                """
                {
                  "paymentId": "pay_123",
                  "amount": 5000
                }
                """;

        CanonicalRequestPayload result =
                canonicalizer.canonicalize(
                        input
                );

        assertThat(result.canonicalJson())
                .isEqualTo(
                        """
                        {"amount":5000,"paymentId":"pay_123"}"""
                );

        assertThat(result.hash().value())
                .isEqualTo(
                        "fe033f2b85fbcc6f563d9ecdcb7d04e40e3a8ef239f900db3b0b34bbe3e21ff7"
                );
    }

    @Test
    void producesSameIdentityRegardlessOfObjectPropertyOrder() {
        String first =
                """
                {
                  "paymentId": "pay_123",
                  "amount": 5000
                }
                """;

        String second =
                """
                {
                  "amount": 5000,
                  "paymentId": "pay_123"
                }
                """;

        CanonicalRequestPayload firstResult =
                canonicalizer.canonicalize(
                        first
                );

        CanonicalRequestPayload secondResult =
                canonicalizer.canonicalize(
                        second
                );

        assertThat(
                firstResult.canonicalJson()
        )
                .isEqualTo(
                        secondResult.canonicalJson()
                );

        assertThat(
                firstResult.hash()
        )
                .isEqualTo(
                        secondResult.hash()
                );
    }

    @Test
    void changesIdentityWhenPayloadChanges() {
        CanonicalRequestPayload first =
                canonicalizer.canonicalize(
                        """
                        {
                          "amount": 5000
                        }
                        """
                );

        CanonicalRequestPayload second =
                canonicalizer.canonicalize(
                        """
                        {
                          "amount": 5001
                        }
                        """
                );

        assertThat(first.hash())
                .isNotEqualTo(
                        second.hash()
                );
    }

    @Test
    void rejectsDuplicatePropertyNames() {
        assertThatThrownBy(
                () -> canonicalizer.canonicalize(
                        """
                        {
                          "amount": 5000,
                          "amount": 9000
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidRequestPayloadException.class
                );
    }

    @Test
    void rejectsArrayAsTopLevelPayload() {
        assertThatThrownBy(
                () -> canonicalizer.canonicalize(
                        """
                        [
                          {
                            "amount": 5000
                          }
                        ]
                        """
                )
        )
                .isInstanceOf(
                        InvalidRequestPayloadException.class
                )
                .hasMessageContaining(
                        "JSON object"
                );
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(
                () -> canonicalizer.canonicalize(
                        """
                        {
                          "amount":
                        }
                        """
                )
        )
                .isInstanceOf(
                        InvalidRequestPayloadException.class
                );
    }

    @Test
    void rejectsBlankPayload() {
        assertThatThrownBy(
                () -> canonicalizer.canonicalize(
                        " "
                )
        )
                .isInstanceOf(
                        InvalidRequestPayloadException.class
                );
    }
}