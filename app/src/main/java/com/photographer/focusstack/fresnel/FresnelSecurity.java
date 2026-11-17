package com.photographer.focusstack.fresnel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@ConditionalOnProperty(name = "focusstack.fresnel.enabled", havingValue = "true")
class FresnelSecurity {
    @Bean @Order(3)
    SecurityFilterChain fresnelServiceFilterChain(HttpSecurity http, FresnelPairings pairings, MeterRegistry metrics,
            @Value("${focusstack.fresnel.trust-private-network:false}") boolean trustNetwork) throws Exception {
        Gauge.builder("photographer.fresnel.pairings.active", pairings,
                store -> store.list().stream().filter(p -> !p.state().equals("REVOKED")).count()).register(metrics);
        Gauge.builder("photographer.fresnel.pairings.connected", pairings,
                store -> store.list().stream().filter(p -> p.state().equals("CONNECTED")).count()).register(metrics);
        return http.securityMatcher(FresnelIngress.ROOT + "/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(new FresnelIngress(pairings, trustNetwork, metrics), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
