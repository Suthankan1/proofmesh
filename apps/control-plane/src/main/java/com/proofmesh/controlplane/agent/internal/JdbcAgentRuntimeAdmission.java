package com.proofmesh.controlplane.agent.internal;

import com.proofmesh.controlplane.agent.AgentRuntimeAdmission;
import com.proofmesh.controlplane.agent.AuthenticatedAgent;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcAgentRuntimeAdmission implements AgentRuntimeAdmission {
    private final JdbcTemplate jdbc;
    private final int limit;
    JdbcAgentRuntimeAdmission(JdbcTemplate jdbc,
            @Value("${proofmesh.agent.requests-per-minute:60}") int limit) {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Invalid agent admission limit");
        this.jdbc = jdbc;
        this.limit = limit;
    }
    @Override @Transactional
    public boolean admit(AuthenticatedAgent agent) {
        var admitted = jdbc.query("""
                INSERT INTO proofmesh.agent_runtime_limits(organization_id,agent_id,window_minute,requests)
                VALUES (?,?,floor(extract(epoch FROM CURRENT_TIMESTAMP)/60)::bigint,1)
                ON CONFLICT (organization_id,agent_id) DO UPDATE
                SET window_minute=EXCLUDED.window_minute,
                    requests=CASE WHEN agent_runtime_limits.window_minute=EXCLUDED.window_minute
                        THEN agent_runtime_limits.requests+1 ELSE 1 END
                WHERE agent_runtime_limits.window_minute<>EXCLUDED.window_minute
                    OR agent_runtime_limits.requests<?
                RETURNING requests
                """, (rs,row)->rs.getInt(1), agent.organizationId(),agent.agentId(),limit);
        boolean allowed = !admitted.isEmpty();
        jdbc.update("""
                INSERT INTO proofmesh.agent_runtime_audit(id,organization_id,agent_id,outcome)
                VALUES (?,?,?,?)
                """,UUID.randomUUID(),agent.organizationId(),agent.agentId(),allowed?"ADMITTED":"RATE_LIMITED");
        return allowed;
    }
}
