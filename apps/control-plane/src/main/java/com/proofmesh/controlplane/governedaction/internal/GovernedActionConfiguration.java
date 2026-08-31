package com.proofmesh.controlplane.governedaction.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class GovernedActionConfiguration {

    @Bean
    Clock proofMeshClock() {
        return Clock.systemUTC();
    }
}