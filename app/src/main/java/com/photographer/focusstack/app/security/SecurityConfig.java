package com.photographer.focusstack.app.security;

import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.app.api.RealtimeApiPaths;
import com.photographer.focusstack.app.web.BrowserRoutes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.util.WebUtils;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByUsername(username)
                .map(user -> User.withUsername(user.getUsername())
                        .password(user.getPasswordHash())
                        .roles(user.getRole().name())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    @Order(1)
    public SecurityFilterChain crossOriginSocketSecurity(HttpSecurity http) throws Exception {
        var plainSocket = PathPatternRequestMatcher.pathPattern(RealtimeApiPaths.PLAIN_ENDPOINT);
        // A client on another origin must supply its own credentials on the upgrade.
        return http.securityMatcher(request -> plainSocket.matches(request)
                        && !WebUtils.isSameOrigin(new ServletServerHttpRequest(request)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain plainWebSocketSecurity(HttpSecurity http) throws Exception {
        // Only the same-origin plain socket reads the browser grant. Form login still works.
        return http.securityMatcher(RealtimeApiPaths.PLAIN_ENDPOINT)
                .securityContext(context -> context.securityContextRepository(BrowserSocketSession.handshakeRepository()))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Public endpoints: API docs, static frontend assets, and health check
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.svg",
                        "/favicon.ico").permitAll()
                .requestMatchers(HttpMethod.GET, BrowserRoutes.PATHS.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.GET,
                        ApiPaths.V1 + "/analysis/health",
                        ApiPaths.LEGACY + "/analysis/health").permitAll()
                // Admin-only management endpoints
                .requestMatchers(
                        ApiPaths.V1 + "/admin/**",
                        ApiPaths.LEGACY + "/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,
                        ApiPaths.V1 + "/jobs/reindex",
                        ApiPaths.LEGACY + "/jobs/reindex").hasRole("ADMIN")
                // All other requests (API, WebSocket) require authentication
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .defaultSuccessUrl("/", false)
                .failureUrl("/login?error")
                .permitAll()
            )
            .httpBasic(basic -> {})
            .csrf(csrf -> csrf
                // Disable CSRF for REST API endpoints (stateless clients use HTTP Basic)
                .ignoringRequestMatchers("/api/**", "/ws/**")
            );
        return http.build();
    }
}
