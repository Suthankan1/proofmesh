package com.proofmesh.controlplane.governedaction;

public class IdempotencyConflictException
        extends RuntimeException {

    public IdempotencyConflictException() {
        super(
                "The idempotency key is already bound to a different governed request."
        );
    }
}