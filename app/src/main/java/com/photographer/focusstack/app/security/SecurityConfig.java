package com.photographer.focusstack.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the Focus Stack application.
 *
 * <h3>Access model</h3>
 * <ul>
 *   <li><strong>ADMIN</strong> — full access: camera profiles, job management,
 *       analysis endpoints, user administration</li>
 *   <li><strong>USER</strong>  — read-only camera status / live-view,
 *       start/stop jobs, view gallery</li>
 *   <li><strong>Anonymous</strong> — Swagger UI, OpenAPI docs, static frontend
 *       assets, WebSocket handshake, and the health endpoint are public</li>
 * </ul>
 *
 * <p>A default admin account is created on first startup when no users exist.
 * The default credentials are controlled by {@code focusstack.security.admin-username}
 * and {@code focusstack.security.admin-password} properties (both configurable
 * via environment variables in Docker deployments).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> {
            UserEntity user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException(
                            "User not found: " + username));
            return User.withUsername(user.getUsername())
                    .password(user.getPasswordHash())
                    .roles(user.getRole().name())
                    .build();
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Public: Swagger UI + OpenAPI spec
                .requestMatchers(
                    "/swagger-ui/**", "/swagger-ui.html",
                    "/v3/api-docs/**", "/v3/api-docs"
                ).permitAll()
                // Public: WebSocket upgrade
                .requestMatchers("/ws/**").permitAll()
                // Public: static frontend (SPA) and root
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg", "/icons.svg").permitAll()
                // Public: health check
                .requestMatchers("/api/analysis/health").permitAll()
                // USER and ADMIN: read-only camera and job operations
                .requestMatchers(HttpMethod.GET, "/api/cameras/**", "/api/jobs/**").hasAnyRole("USER", "ADMIN")
                // ADMIN only: camera profile mutations, job control
                .requestMatchers(HttpMethod.POST, "/api/cameras/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/cameras/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/cameras/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/jobs/**").hasAnyRole("USER", "ADMIN")
                // ADMIN only: analysis endpoints and reindexing
                .requestMatchers("/api/analysis/**").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/jobs/reindex").hasRole("ADMIN")
                // All other requests require authentication
                .anyRequest().authenticated()
            )
            // HTTP Basic for REST clients (e.g. curl, Postman)
            .httpBasic(basic -> {})
            // Form login for browser-based access
            .formLogin(form -> form
                .defaultSuccessUrl("/", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/")
                .permitAll()
            )
            // Disable CSRF for the API (React SPA uses bearer/basic auth)
            .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * Creates the default admin account on startup if no users exist.
     *
     * <p>The credentials are controlled by:
     * <ul>
     *   <li>{@code focusstack.security.admin-username} (default: {@code admin})</li>
     *   <li>{@code focusstack.security.admin-password} (default: {@code changeme})</li>
     * </ul>
     * Set {@code FOCUSSTACK_SECURITY_ADMIN_PASSWORD} in your environment to override in Docker.
     */
    @Bean
    public CommandLineRunner createDefaultAdminUser(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${focusstack.security.admin-username:admin}") String adminUsername,
            @Value("${focusstack.security.admin-password:changeme}") String adminPassword) {

        return args -> {
            if (!userRepository.existsByUsername(adminUsername)) {
                UserEntity admin = new UserEntity(
                        adminUsername,
                        passwordEncoder.encode(adminPassword),
                        UserRole.ADMIN
                );
                userRepository.save(admin);
                log.info("Created default admin user '{}'. Change the password via FOCUSSTACK_SECURITY_ADMIN_PASSWORD.",
                        adminUsername);
            }
        };
    }
}
