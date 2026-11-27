package com.photographer.focusstack.fresnel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.util.WebUtils;

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
        var csrfTokens = new CookieCsrfTokenRepository();
        csrfTokens.setCookieName("FRESNEL-CSRF");
        return http.securityMatcher(FresnelIngress.ROOT + "/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Service clients supply a pairing code or bearer token explicitly, without browser session credentials.
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .ignoringRequestMatchers(FresnelSecurity::isDirectOrSameOrigin))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, failure) -> {
                    // Write the private API error directly, outside the browser login chain's error dispatch.
                    var problem = new FresnelProblem(403, "REQUEST_FORBIDDEN");
                    response.setStatus(problem.status());
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    FresnelPlan.JSON.writeValue(response.getOutputStream(), problem.body());
                }))
                .addFilterBefore(new FresnelIngress(pairings, trustNetwork, metrics), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    private static boolean isDirectOrSameOrigin(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equals(fetchSite) || "same-site".equals(fetchSite)) return false;
        try {
            // Older browsers may only send Origin. Service clients can omit both headers.
            return WebUtils.isSameOrigin(new ServletServerHttpRequest(request));
        } catch (IllegalArgumentException invalidOrigin) {
            return false;
        }
    }
}
