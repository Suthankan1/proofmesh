package com.proofmesh.controlplane.agent.internal;

import com.proofmesh.controlplane.agent.AgentCredentialService;
import com.proofmesh.controlplane.agent.AuthenticatedAgent;
import com.proofmesh.controlplane.agent.IssuedAgentCredential;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
class JdbcAgentCredentialService implements AgentCredentialService {
    private static final Duration MAX_VALIDITY = Duration.ofDays(90);
    private static final Pattern FORMAT = Pattern.compile("pm_agent_[A-Za-z0-9_-]{43}");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    JdbcAgentCredentialService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public IssuedAgentCredential rotate(UUID organizationId, UUID agentId, Duration validity) {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(agentId);
        if (validity == null || validity.isNegative() || validity.isZero() || validity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalArgumentException("credential validity must be positive and at most 90 days");
        }
        // The active tenant/agent row serializes competing rotations, not a caller-provided identity.
        var matches = jdbc.query("""
                SELECT a.id FROM proofmesh.agents a JOIN proofmesh.organizations o ON o.id=a.organization_id
                WHERE a.id=? AND a.organization_id=? AND a.status='ACTIVE' AND o.status='ACTIVE'
                FOR UPDATE OF a,o
                """, (rs, row) -> rs.getObject("id", UUID.class), agentId, organizationId);
        if (matches.size() != 1) {
            throw new IllegalArgumentException("active scoped agent unavailable");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(validity);
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String secret = "pm_agent_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                UPDATE proofmesh.agent_credentials SET revoked_at=?
                WHERE organization_id=? AND agent_id=? AND revoked_at IS NULL
                """, timestamp(now), organizationId, agentId);
        jdbc.update("""
                INSERT INTO proofmesh.agent_credentials(id,organization_id,agent_id,credential_hash,created_at,expires_at)
                VALUES (?,?,?,?,?,?)
                """, id, organizationId, agentId, hash(secret), timestamp(now), timestamp(expiresAt));
        return new IssuedAgentCredential(id, secret, expiresAt);
    }

    @Override
    @Transactional
    public boolean revoke(UUID organizationId, UUID agentId) {
        Objects.requireNonNull(organizationId);Objects.requireNonNull(agentId);
        var scoped=jdbc.query("SELECT id FROM proofmesh.agents WHERE id=? AND organization_id=? FOR UPDATE",
                (rs,row)->rs.getObject(1),agentId,organizationId);
        if(scoped.size()!=1) return false;
        jdbc.update("UPDATE proofmesh.agent_credentials SET revoked_at=? WHERE organization_id=? AND agent_id=? AND revoked_at IS NULL",
                timestamp(clock.instant()),organizationId,agentId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticatedAgent> authenticate(String opaqueCredential) {
        if (opaqueCredential == null || !FORMAT.matcher(opaqueCredential).matches()) return Optional.empty();
        var matches = jdbc.query("""
                SELECT c.organization_id,c.agent_id FROM proofmesh.agent_credentials c
                JOIN proofmesh.agents a ON a.id=c.agent_id AND a.organization_id=c.organization_id
                JOIN proofmesh.organizations o ON o.id=c.organization_id
                WHERE c.credential_hash=? AND c.revoked_at IS NULL AND c.expires_at>?
                AND a.status='ACTIVE' AND o.status='ACTIVE'
                """, (rs, row) -> new AuthenticatedAgent(rs.getObject("organization_id", UUID.class),
                rs.getObject("agent_id", UUID.class)), hash(opaqueCredential), timestamp(clock.instant()));
        return matches.stream().findFirst();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
