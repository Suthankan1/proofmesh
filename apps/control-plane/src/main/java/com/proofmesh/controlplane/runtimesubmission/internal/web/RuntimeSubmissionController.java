package com.proofmesh.controlplane.runtimesubmission.internal.web;

import com.proofmesh.controlplane.agent.AuthenticatedAgent;
import com.proofmesh.controlplane.governedaction.IdempotencyConflictException;
import com.proofmesh.controlplane.runtimesubmission.internal.RuntimeSubmissionService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/runtime/actions")
class RuntimeSubmissionController {
    private static final Set<String> FIELDS=Set.of("idempotencyKey","toolName","operationName","requestPayloadJson");
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final RuntimeSubmissionService submissions;
    RuntimeSubmissionController(RuntimeSubmissionService submissions) {this.submissions=submissions;}

    @PostMapping(consumes="application/json")
    RuntimeSubmissionService.SubmissionResult submit(Authentication authentication, HttpServletRequest servletRequest) {
        if(!(authentication.getPrincipal() instanceof AuthenticatedAgent identity))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Map<String,String> request;
        try {
            byte[] bytes=servletRequest.getInputStream().readNBytes(131073);
            if(bytes.length>131072) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            request=JSON.readValue(bytes,new TypeReference<Map<String,String>>(){});
        } catch(IOException | JacksonException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid runtime JSON");
        }
        if(request==null || !request.keySet().equals(FIELDS) || request.values().stream().anyMatch(value->value==null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Exactly the runtime request fields are required");
        if(request.get("requestPayloadJson").getBytes(StandardCharsets.UTF_8).length>65536)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        try {
            return submissions.submit(identity,request.get("idempotencyKey"),request.get("toolName"),
                    request.get("operationName"),request.get("requestPayloadJson"));
        } catch(IdempotencyConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key is bound to another request");
        } catch(IllegalArgumentException malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid runtime request");
        } catch(DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Governance state unavailable");
        }
    }
}
