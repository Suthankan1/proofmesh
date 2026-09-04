package com.proofmesh.controlplane.executiongrant.internal;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

@RestController
@ConditionalOnProperty(
        prefix = "proofmesh.execution-grant.signing",
        name = "enabled",
        havingValue = "true"
)
class ExecutionGrantJwksController {

    private static final String CACHE_CONTROL_VALUE = "public, max-age=60, must-revalidate";

    private final ExecutionGrantPublicKeyProvider publicKeyProvider;

    ExecutionGrantJwksController(ExecutionGrantPublicKeyProvider publicKeyProvider) {
        this.publicKeyProvider = Objects.requireNonNull(publicKeyProvider, "publicKeyProvider must not be null");
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> getJwks() {
        ECKey jwk = new ECKey.Builder(Curve.P_256, publicKeyProvider.activePublicKey())
                .keyID(publicKeyProvider.activeKeyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .build();

        JWKSet jwkSet = new JWKSet(jwk.toPublicJWK());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jwkSet.toJSONObject(true));
    }
}
