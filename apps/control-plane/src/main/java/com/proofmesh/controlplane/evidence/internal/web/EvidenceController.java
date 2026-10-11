package com.proofmesh.controlplane.evidence.internal.web;

import com.proofmesh.controlplane.identity.CurrentOrganization;
import com.proofmesh.controlplane.identity.OrganizationContext;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only exact-action graph, assembled in one PostgreSQL statement snapshot. */
@RestController
@RequestMapping("/api/v1/evidence/actions")
class EvidenceController {
    private final JdbcTemplate jdbc;
    EvidenceController(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SECURITY_OPERATOR','PLATFORM_ADMIN')")
    ResponseEntity<String> export(@CurrentOrganization OrganizationContext organization, @PathVariable UUID id) {
        try {
            var rows=jdbc.query("""
                    SELECT jsonb_build_object(
                        'schemaVersion',1, 'exportedAt',CURRENT_TIMESTAMP, 'organizationId',a.organization_id,
                        'action',to_jsonb(a)-'request_payload'-'idempotency_key',
                        'riskAssessment',(SELECT to_jsonb(r) FROM proofmesh.risk_assessments r
                            WHERE r.governed_action_id=a.id AND r.organization_id=a.organization_id),
                        'decision',(SELECT to_jsonb(d) FROM proofmesh.governance_decisions d
                            WHERE d.governed_action_id=a.id AND d.organization_id=a.organization_id),
                        'policyVersion',(SELECT to_jsonb(v) FROM proofmesh.policy_versions v
                            JOIN proofmesh.governance_decisions d ON d.policy_version_id=v.id AND d.organization_id=v.organization_id
                            WHERE d.governed_action_id=a.id AND d.organization_id=a.organization_id),
                        'approval',(SELECT to_jsonb(p)-'rationale' FROM proofmesh.approval_requests p
                            WHERE p.governed_action_id=a.id AND p.organization_id=a.organization_id),
                        'issuance',(SELECT to_jsonb(i) FROM proofmesh.runtime_grant_issuances i
                            WHERE i.governed_action_id=a.id AND i.organization_id=a.organization_id),
                        'executions',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.claimed_at,c.grant_id)
                            FROM public.execution_grant_claims c WHERE c.governed_action_id=a.id AND c.organization_id=a.organization_id),'[]'::jsonb)
                    )::text FROM proofmesh.governed_actions a WHERE a.id=? AND a.organization_id=?
                    """,(rs,row)->rs.getString(1),id,organization.organizationId());
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(rows.getFirst());
        } catch(DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Evidence state unavailable");
        }
    }
}
