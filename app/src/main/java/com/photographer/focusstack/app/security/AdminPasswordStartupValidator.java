package com.photographer.focusstack.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Validates the configured admin password at startup and fails fast on unsafe defaults.
 */
@Component
public class AdminPasswordStartupValidator implements ApplicationRunner {

    private static final Set<String> DISALLOWED_VALUES = Set.of("focusstack", "changeme");

    private final String adminPassword;

    public AdminPasswordStartupValidator(
            @Value("${focusstack.security.admin-password:}") String adminPassword) {
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException("""
                    FOCUSSTACK_SECURITY_ADMIN_PASSWORD is required and must not be blank.
                    Set focusstack.security.admin-password (or FOCUSSTACK_SECURITY_ADMIN_PASSWORD) before startup.
                    """.trim());
        }
        String normalized = adminPassword.trim().toLowerCase(Locale.ROOT);
        if (DISALLOWED_VALUES.contains(normalized)) {
            throw new IllegalStateException("""
                    Insecure admin password rejected: use a non-default value.
                    Forbidden values: focusstack, changeme.
                    Configure FOCUSSTACK_SECURITY_ADMIN_PASSWORD before startup.
                    """.trim());
        }
    }
}
