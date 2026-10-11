package com.proofmesh.controlplane.runtimesubmission.internal;

import com.proofmesh.controlplane.executiongrant.*;
import com.proofmesh.controlplane.governedaction.GovernedAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One externally observable live authority per runtime action, even across replicas. */
@Service
class RuntimeGrantIssuance {
    private final ObjectProvider<ExecutionGrantIssuer> issuers;
    private final JdbcTemplate jdbc;
    RuntimeGrantIssuance(ObjectProvider<ExecutionGrantIssuer> issuers, JdbcTemplate jdbc) {
        this.issuers=issuers; this.jdbc=jdbc;
    }
    @Transactional public Authority issue(GovernedAction action) {
        var issuer=issuers.getIfAvailable();
        if(issuer==null) return new Authority(null,"SIGNING_DISABLED");
        var locked=jdbc.query("SELECT id FROM proofmesh.governed_actions WHERE id=? AND organization_id=? AND agent_id=? FOR UPDATE",
                (rs,row)->rs.getObject(1),action.id(),action.organizationId(),action.agentId());
        if(locked.size()!=1) return new Authority(null,"STATE_UNAVAILABLE");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM proofmesh.runtime_grant_issuances WHERE governed_action_id=? AND organization_id=?)",
                Boolean.class,action.id(),action.organizationId()))) return new Authority(null,"ALREADY_ISSUED");
        var result=issuer.issue(action.organizationId(),action.id());
        if(!(result instanceof ExecutionGrantIssuanceResult.Issued issued)) return new Authority(null,result.getClass().getSimpleName());
        String token=issued.grant().compactToken();
        jdbc.update("INSERT INTO proofmesh.runtime_grant_issuances(governed_action_id,organization_id,agent_id,grant_hash) VALUES (?,?,?,?)",
                action.id(),action.organizationId(),action.agentId(),hash(token));
        return new Authority(token,"ISSUED");
    }
    private static String hash(String token) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException unavailable) {throw new IllegalStateException("SHA-256 unavailable",unavailable);}
    }
    record Authority(String token,String status) {
        @Override public String toString(){return "Authority[status="+status+",token=[REDACTED]]";}
    }
}
