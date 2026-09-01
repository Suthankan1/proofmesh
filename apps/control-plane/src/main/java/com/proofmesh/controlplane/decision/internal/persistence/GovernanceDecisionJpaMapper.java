package com.proofmesh.controlplane.decision.internal.persistence;

import com.proofmesh.controlplane.decision.DecisionReasonCode;
import com.proofmesh.controlplane.decision.GovernanceDecision;
import com.proofmesh.controlplane.decision.GovernanceDecisionIntegrityException;
import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.policy.PolicyRuleId;
import com.proofmesh.controlplane.policy.PolicyVersionId;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
class GovernanceDecisionJpaMapper {

    private final JsonMapper jsonMapper;

    GovernanceDecisionJpaMapper(
            JsonMapper jsonMapper
    ) {
        this.jsonMapper =
                Objects.requireNonNull(
                        jsonMapper,
                        "jsonMapper must not be null"
                );
    }

    GovernanceDecision toDomain(
            GovernanceDecisionJpaEntity entity
    ) {
        Objects.requireNonNull(
                entity,
                "entity must not be null"
        );

        try {
            return new GovernanceDecision(
                    entity.id(),
                    entity.organizationId(),
                    entity.governedActionId(),
                    new PolicyVersionId(
                            entity.policyVersionId()
                    ),
                    matchedPolicyRuleId(
                            entity
                    ),
                    entity.outcome(),
                    new RiskScore(
                            entity.riskScore()
                    ),
                    parseReasonCodes(
                            entity.reasonCodes()
                    ),
                    entity.decidedAt()
            );
        } catch (GovernanceDecisionIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GovernanceDecisionIntegrityException(
                    "stored governance decision failed integrity validation",
                    exception
            );
        }
    }

    String reasonCodesJson(
            GovernanceDecision decision
    ) {
        Objects.requireNonNull(
                decision,
                "decision must not be null"
        );

        try {
            ArrayNode array =
                    jsonMapper.createArrayNode();

            for (DecisionReasonCode reasonCode
                    : decision.reasonCodes()) {
                array.add(
                        reasonCode.value()
                );
            }

            return jsonMapper.writeValueAsString(
                    array
            );
        } catch (RuntimeException exception) {
            throw new GovernanceDecisionIntegrityException(
                    "governance decision reason codes could not be serialized",
                    exception
            );
        }
    }

    private PolicyRuleId matchedPolicyRuleId(
            GovernanceDecisionJpaEntity entity
    ) {
        if (entity.matchedPolicyRuleId()
                == null) {
            return null;
        }

        return new PolicyRuleId(
                entity.matchedPolicyRuleId()
        );
    }

    private List<DecisionReasonCode> parseReasonCodes(
            JsonNode reasonCodesNode
    ) {
        if (reasonCodesNode == null
                || !reasonCodesNode.isArray()) {
            throw new GovernanceDecisionIntegrityException(
                    "stored governance decision reason codes must be a JSON array"
            );
        }

        if (reasonCodesNode.isEmpty()) {
            throw new GovernanceDecisionIntegrityException(
                    "stored governance decision reason codes must not be empty"
            );
        }

        List<DecisionReasonCode> reasonCodes =
                new ArrayList<>();

        Set<DecisionReasonCode> seen =
                new HashSet<>();

        for (JsonNode node : reasonCodesNode) {
            if (!node.isTextual()) {
                throw new GovernanceDecisionIntegrityException(
                        "stored governance decision reason codes must contain only strings"
                );
            }

            DecisionReasonCode reasonCode =
                    new DecisionReasonCode(
                            node.asText()
                    );

            if (!seen.add(
                    reasonCode
            )) {
                throw new GovernanceDecisionIntegrityException(
                        "stored governance decision reason codes must not contain duplicates"
                );
            }

            reasonCodes.add(
                    reasonCode
            );
        }

        return List.copyOf(
                reasonCodes
        );
    }
}