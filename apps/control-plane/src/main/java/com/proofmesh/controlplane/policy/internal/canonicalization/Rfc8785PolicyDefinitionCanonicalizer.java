package com.proofmesh.controlplane.policy.internal.canonicalization;

import com.proofmesh.controlplane.policy.CanonicalPolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinition;
import com.proofmesh.controlplane.policy.PolicyDefinitionCanonicalizationException;
import com.proofmesh.controlplane.policy.PolicyDefinitionCanonicalizer;
import com.proofmesh.controlplane.policy.PolicyDefinitionHash;
import com.proofmesh.controlplane.policy.PolicyRule;

import org.erdtman.jcs.JsonCanonicalizer;

import org.springframework.stereotype.Component;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Component
class Rfc8785PolicyDefinitionCanonicalizer
        implements PolicyDefinitionCanonicalizer {

    private final JsonMapper jsonMapper;

    Rfc8785PolicyDefinitionCanonicalizer(
            JsonMapper jsonMapper
    ) {
        this.jsonMapper =
                Objects.requireNonNull(
                        jsonMapper,
                        "jsonMapper must not be null"
                );
    }

    @Override
    public CanonicalPolicyDefinition canonicalize(
            PolicyDefinition definition
    ) {
        Objects.requireNonNull(
                definition,
                "definition must not be null"
        );

        try {
            ObjectNode root =
                    jsonMapper.createObjectNode();

            ArrayNode rulesNode =
                    root.putArray(
                            "rules"
                    );

            for (PolicyRule rule
                    : definition.rules()) {

                ObjectNode ruleNode =
                        rulesNode.addObject();

                ruleNode.put(
                        "id",
                        rule.id()
                                .value()
                                .toString()
                );

                ruleNode.put(
                        "priority",
                        rule.priority()
                                .value()
                );

                ObjectNode targetNode =
                        ruleNode.putObject(
                                "target"
                        );

                targetNode.put(
                        "tool",
                        rule.target()
                                .tool()
                );

                targetNode.put(
                        "operation",
                        rule.target()
                                .operation()
                );

                ObjectNode riskNode =
                        ruleNode.putObject(
                                "risk"
                        );

                riskNode.put(
                        "minimum",
                        rule.riskThreshold()
                                .minimum()
                );

                ruleNode.put(
                        "effect",
                        rule.effect()
                                .name()
                );

                ruleNode.put(
                        "reasonCode",
                        rule.reasonCode()
                                .value()
                );
            }

            String serializedJson =
                    jsonMapper.writeValueAsString(
                            root
                    );

            String canonicalJson =
                    new JsonCanonicalizer(
                            serializedJson
                    )
                            .getEncodedString();

            return new CanonicalPolicyDefinition(
                    canonicalJson,
                    new PolicyDefinitionHash(
                            sha256(
                                    canonicalJson
                            )
                    )
            );
        } catch (Exception exception) {
            throw new PolicyDefinitionCanonicalizationException(
                    "Unable to canonicalize policy definition",
                    exception
            );
        }
    }

    private String sha256(
            String canonicalJson
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] hash =
                    digest.digest(
                            canonicalJson.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of()
                    .formatHex(
                            hash
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }
}