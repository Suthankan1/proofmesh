package com.proofmesh.controlplane.policy;

import com.proofmesh.controlplane.governedaction.OperationName;
import com.proofmesh.controlplane.governedaction.ToolName;

import java.util.Objects;

public record PolicyTarget(
        String tool,
        String operation
) {

    public static final String ANY =
            "*";

    private static final int MAX_LENGTH =
            160;

    public PolicyTarget {
        validate(
                tool,
                "tool"
        );

        validate(
                operation,
                "operation"
        );
    }

    public boolean matches(
            ToolName toolName,
            OperationName operationName
    ) {
        Objects.requireNonNull(
                toolName,
                "toolName must not be null"
        );

        Objects.requireNonNull(
                operationName,
                "operationName must not be null"
        );

        boolean toolMatches =
                ANY.equals(tool)
                        || tool.equals(
                                toolName.value()
                        );

        boolean operationMatches =
                ANY.equals(operation)
                        || operation.equals(
                                operationName.value()
                        );

        return toolMatches
                && operationMatches;
    }

    private static void validate(
            String value,
            String fieldName
    ) {
        Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must not be blank"
            );
        }

        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must not exceed 160 characters"
            );
        }

        if (value.contains("*")
                && !ANY.equals(value)) {
            throw new IllegalArgumentException(
                    fieldName
                            + " supports only exact matching or '*'"
            );
        }
    }
}