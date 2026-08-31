package com.proofmesh.controlplane.governedaction;

public class InvalidRequestPayloadException
        extends IllegalArgumentException {

    public InvalidRequestPayloadException(
            String message
    ) {
        super(message);
    }

    public InvalidRequestPayloadException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}