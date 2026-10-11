package com.proofmesh.controlplane.approval.internal.web;

import com.proofmesh.controlplane.approval.*;
import com.proofmesh.controlplane.governedaction.GovernedActionRepository;
import com.proofmesh.controlplane.identity.CurrentOrganization;
import com.proofmesh.controlplane.identity.OrganizationContext;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalController {
    private final ApprovalRequestRepository repository;
    private final ApprovalRequestResolver resolver;
    private final GovernedActionRepository actions;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    ApprovalController(ApprovalRequestRepository repository, ApprovalRequestResolver resolver,
            GovernedActionRepository actions, JdbcTemplate jdbc, Clock clock) {
        this.repository=repository; this.resolver=resolver; this.actions=actions; this.jdbc=jdbc; this.clock=clock;
    }
    @GetMapping
    @PreAuthorize("hasAnyRole('APPROVER','VIEWER','SECURITY_OPERATOR','PLATFORM_ADMIN')")
    ResponseEntity<List<ApprovalView>> pending(@CurrentOrganization OrganizationContext organization,
            @RequestParam(defaultValue="50") int limit) {
        if(limit<1 || limit>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var ids=jdbc.query("""
                SELECT id FROM proofmesh.approval_requests
                WHERE organization_id=? AND status='PENDING'
                ORDER BY requested_at,id LIMIT ?
                """,(rs,row)->rs.getObject("id",UUID.class),organization.organizationId(),limit);
        var views=ids.stream().map(id->repository.findByOrganizationIdAndId(organization.organizationId(),id)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)))
                .map(approval->view(approval,null)).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views);
    }
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('APPROVER','VIEWER','SECURITY_OPERATOR','PLATFORM_ADMIN')")
    ResponseEntity<ApprovalView> detail(@CurrentOrganization OrganizationContext organization, @PathVariable UUID id) {
        var approval=repository.findByOrganizationIdAndId(organization.organizationId(),id)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        var action=actions.findByIdAndOrganizationId(approval.governedActionId(),organization.organizationId())
                .orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(approval,action.requestPayload().canonicalJson()));
    }
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('APPROVER')")
    ResponseEntity<ApprovalView> approve(@CurrentOrganization OrganizationContext organization,
            @PathVariable UUID id, @RequestBody Map<String,String> request) {
        return resolve(organization,id,request,true);
    }
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('APPROVER')")
    ResponseEntity<ApprovalView> reject(@CurrentOrganization OrganizationContext organization,
            @PathVariable UUID id, @RequestBody Map<String,String> request) {
        return resolve(organization,id,request,false);
    }
    private ResponseEntity<ApprovalView> resolve(OrganizationContext organization, UUID id,
            Map<String,String> request, boolean approve) {
        if(!request.keySet().equals(Set.of("rationale")) || request.get("rationale")==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only rationale is accepted");
        try {
            var actor=new ApprovalActorId(organization.oidcSubject());
            var rationale=new ApprovalRationale(request.get("rationale"));
            var result=approve?resolver.approve(organization.organizationId(),id,actor,rationale,clock.instant())
                    :resolver.reject(organization.organizationId(),id,actor,rationale,clock.instant());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(result,null));
        } catch(ApprovalRequestNotFoundException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch(ApprovalResolutionConflictException | ApprovalRequestExpiredException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval cannot be resolved");
        } catch(IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid rationale");
        } catch(DataAccessException | ApprovalRequestIntegrityException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Approval state unavailable");
        }
    }
    private ApprovalView view(ApprovalRequest approval, String payload) {
        return new ApprovalView(approval.id(),approval.organizationId(),approval.agentId(),approval.governedActionId(),
                approval.governanceDecisionId(),approval.toolName().value(),approval.operationName().value(),
                approval.requestPayloadHash().value(),approval.status().name(),approval.requestedAt(),approval.expiresAt(),
                approval.isApprovedAndValidAt(clock.instant()),payload);
    }
    record ApprovalView(UUID id, UUID organizationId, UUID agentId, UUID governedActionId, UUID governanceDecisionId,
            String toolName, String operationName, String payloadHash, String status, Instant requestedAt, Instant expiresAt,
            boolean validAuthorization, String requestPayloadJson) {
        @Override public String toString() {return "ApprovalView[id="+id+", status="+status+", payload=[REDACTED]]";}
    }
}
