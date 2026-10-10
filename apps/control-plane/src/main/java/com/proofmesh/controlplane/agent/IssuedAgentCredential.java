package com.proofmesh.controlplane.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Plaintext is available only at issuance; never persist or log this value. */
public final class IssuedAgentCredential {
    private final UUID id;
    private final String secret;
    private final Instant expiresAt;

    public IssuedAgentCredential(UUID id, String secret, Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        this.secret = Objects.requireNonNull(secret);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }
    public UUID id() { return id; }
    public String secret() { return secret; }
    public Instant expiresAt() { return expiresAt; }
    @Override public String toString() { return "IssuedAgentCredential[secret=[REDACTED]]"; }
}
