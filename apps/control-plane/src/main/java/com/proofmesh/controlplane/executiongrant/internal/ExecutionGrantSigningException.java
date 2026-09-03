package com.proofmesh.controlplane.executiongrant.internal;

final class ExecutionGrantSigningException extends RuntimeException {

    ExecutionGrantSigningException(String message) {
        super(message);
    }

    ExecutionGrantSigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
