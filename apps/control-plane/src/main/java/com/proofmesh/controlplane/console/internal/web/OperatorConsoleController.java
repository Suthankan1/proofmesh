package com.proofmesh.controlplane.console.internal.web;

import com.proofmesh.controlplane.identity.CurrentOrganization;
import com.proofmesh.controlplane.identity.OrganizationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** A bounded, read-only tenant snapshot; no credentials, raw payloads or authority are returned. */
@RestController
@RequestMapping("/api/v1/console")
class OperatorConsoleController {
    private final JdbcTemplate jdbc;
    OperatorConsoleController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','APPROVER','SECURITY_OPERATOR','PLATFORM_ADMIN')")
    ResponseEntity<String> snapshot(@CurrentOrganization OrganizationContext organization) {
        try {
            String result=jdbc.queryForObject("""
                SELECT jsonb_build_object(
                  'organization',jsonb_build_object('id',o.id,'slug',o.slug,'name',o.name,'status',o.status),
                  'pendingApprovals',(SELECT count(*) FROM proofmesh.approval_requests p
                     WHERE p.organization_id=o.id AND p.status='PENDING' AND p.expires_at>CURRENT_TIMESTAMP),
                  'agents',COALESCE((SELECT jsonb_agg(x.data ORDER BY x.created_at DESC,x.id) FROM (
                     SELECT a.id,a.created_at,jsonb_build_object('id',a.id,'name',a.name,'status',a.status) data
                     FROM proofmesh.agents a WHERE a.organization_id=o.id ORDER BY a.created_at DESC,a.id LIMIT 100
                  ) x),'[]'::jsonb),
                  'policies',COALESCE((SELECT jsonb_agg(x.data ORDER BY x.created_at DESC,x.id) FROM (
                     SELECT v.id,v.created_at,jsonb_build_object('id',v.id,'name',p.name,'version',v.version_number,
                       'state',v.state,'definition',v.definition,'definitionHash',v.definition_hash) data
                     FROM proofmesh.policy_versions v JOIN proofmesh.policies p ON p.id=v.policy_id AND p.organization_id=v.organization_id
                     WHERE v.organization_id=o.id ORDER BY v.created_at DESC,v.id LIMIT 100
                  ) x),'[]'::jsonb),
                  'actions',COALESCE((SELECT jsonb_agg(x.data ORDER BY x.created_at DESC,x.id) FROM (
                     SELECT a.id,a.created_at,jsonb_build_object('id',a.id,'agentId',a.agent_id,'toolName',a.tool_name,
                       'operationName',a.operation_name,'payloadHash',a.request_payload_hash,'createdAt',a.created_at,
                       'outcome',d.outcome,'riskScore',d.risk_score) data
                     FROM proofmesh.governed_actions a LEFT JOIN proofmesh.governance_decisions d
                       ON d.governed_action_id=a.id AND d.organization_id=a.organization_id
                     WHERE a.organization_id=o.id ORDER BY a.created_at DESC,a.id LIMIT 100
                  ) x),'[]'::jsonb)
                )::text FROM proofmesh.organizations o WHERE o.id=?
                """,String.class,organization.organizationId());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(result);
        } catch(DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Console state unavailable");
        }
    }
}
