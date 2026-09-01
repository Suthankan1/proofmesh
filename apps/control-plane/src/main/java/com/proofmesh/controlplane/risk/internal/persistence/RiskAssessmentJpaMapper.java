package com.proofmesh.controlplane.risk.internal.persistence;

import com.proofmesh.controlplane.decision.RiskScore;
import com.proofmesh.controlplane.risk.RiskAssessment;
import com.proofmesh.controlplane.risk.RiskAssessmentIntegrityException;
import com.proofmesh.controlplane.risk.RiskLogicVersion;
import com.proofmesh.controlplane.risk.RiskSeverity;
import com.proofmesh.controlplane.risk.RiskSignal;
import com.proofmesh.controlplane.risk.RiskSignalCode;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
class RiskAssessmentJpaMapper {

    private final ObjectMapper objectMapper;

    RiskAssessmentJpaMapper(
            ObjectMapper objectMapper
    ) {
        this.objectMapper =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null"
                );
    }

    RiskAssessment toDomain(
            RiskAssessmentJpaEntity entity
    ) {
        if (entity == null) {
            throw new RiskAssessmentIntegrityException(
                    "persisted risk assessment must not be null"
            );
        }

        try {
            return new RiskAssessment(
                    entity.id(),
                    entity.organizationId(),
                    entity.governedActionId(),
                    new RiskLogicVersion(
                            entity.logicVersion()
                    ),
                    new RiskScore(
                            entity.riskScore()
                    ),
                    parseSignals(
                            entity.signals()
                    ),
                    entity.assessedAt()
            );
        } catch (RiskAssessmentIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RiskAssessmentIntegrityException(
                    "persisted risk assessment contains invalid domain state",
                    exception
            );
        }
    }

    String toSignalsJson(
            RiskAssessment assessment
    ) {
        Objects.requireNonNull(
                assessment,
                "assessment must not be null"
        );

        ArrayNode signals =
                objectMapper.createArrayNode();

        for (RiskSignal signal : assessment.signals()) {
            ObjectNode node =
                    objectMapper.createObjectNode();

            node.put(
                    "code",
                    signal.code().value()
            );

            node.put(
                    "severity",
                    signal.severity().name()
            );

            node.put(
                    "weight",
                    signal.weight()
            );

            node.put(
                    "explanation",
                    signal.explanation()
            );

            signals.add(
                    node
            );
        }

        return signals.toString();
    }

    private List<RiskSignal> parseSignals(
            JsonNode signalsNode
    ) {
        if (signalsNode == null
                || !signalsNode.isArray()) {
            throw new RiskAssessmentIntegrityException(
                    "persisted risk signals must be a JSON array"
            );
        }

        if (signalsNode.isEmpty()) {
            throw new RiskAssessmentIntegrityException(
                    "persisted risk assessment must contain at least one signal"
            );
        }

        List<RiskSignal> signals =
                new ArrayList<>();

        for (JsonNode signalNode : signalsNode) {
            validateSignalShape(
                    signalNode
            );

            JsonNode codeNode =
                    signalNode.get(
                            "code"
                    );

            JsonNode severityNode =
                    signalNode.get(
                            "severity"
                    );

            JsonNode weightNode =
                    signalNode.get(
                            "weight"
                    );

            JsonNode explanationNode =
                    signalNode.get(
                            "explanation"
                    );

            if (!codeNode.isTextual()
                    || !severityNode.isTextual()
                    || !weightNode.isIntegralNumber()
                    || !explanationNode.isTextual()) {
                throw new RiskAssessmentIntegrityException(
                        "persisted risk signal contains invalid field types"
                );
            }

            RiskSignal signal =
                    new RiskSignal(
                            new RiskSignalCode(
                                    codeNode.asText()
                            ),
                            RiskSeverity.valueOf(
                                    severityNode.asText()
                            ),
                            weightNode.intValue(),
                            explanationNode.asText()
                    );

            signals.add(
                    signal
            );
        }

        return List.copyOf(
                signals
        );
    }

    private void validateSignalShape(
            JsonNode signalNode
    ) {
        if (signalNode == null
                || !signalNode.isObject()) {
            throw new RiskAssessmentIntegrityException(
                    "persisted risk signal must be a JSON object"
            );
        }

        if (signalNode.size() != 4
                || !signalNode.has("code")
                || !signalNode.has("severity")
                || !signalNode.has("weight")
                || !signalNode.has("explanation")) {
            throw new RiskAssessmentIntegrityException(
                    "persisted risk signal has invalid structure"
            );
        }
    }
}