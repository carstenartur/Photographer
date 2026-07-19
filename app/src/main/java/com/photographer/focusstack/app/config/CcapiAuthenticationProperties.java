package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * External-only Canon CCAPI account configuration.
 *
 * <p>Entries are keyed by the exact camera profile id. They are deliberately not
 * part of {@code CameraProfile}, persistence entities or public REST models.</p>
 */
@ConfigurationProperties(prefix = "focusstack.camera.ccapi")
public final class CcapiAuthenticationProperties {

    private Map<String, Credential> credentials = new LinkedHashMap<>();

    public Map<String, Credential> getCredentials() {
        return credentials;
    }

    public void setCredentials(Map<String, Credential> credentials) {
        this.credentials = credentials == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(credentials);
    }

    /** Resolves an exact profile-id entry and rejects incomplete secret configuration. */
    public Optional<ResolvedCredentials> resolve(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        Credential configured = credentials.get(profileId);
        if (configured == null) {
            return Optional.empty();
        }
        String username = configured.getUsername();
        String password = configured.getPassword();
        boolean usernamePresent = username != null && !username.isBlank();
        boolean passwordPresent = password != null && !password.isBlank();
        if (usernamePresent != passwordPresent) {
            throw new IllegalStateException(
                    "Incomplete CCAPI credentials configured for camera profile '" + profileId + "'");
        }
        if (!usernamePresent) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedCredentials(username, password.toCharArray()));
    }

    /** Mutable binding target. Its string representation never exposes secret values. */
    public static final class Credential {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        @Override
        public String toString() {
            return "Credential[usernameConfigured="
                    + (username != null && !username.isBlank())
                    + ", password=<redacted>]";
        }
    }

    /** Immutable transport value with defensive password copying and redacted text output. */
    public static final class ResolvedCredentials {
        private final String username;
        private final char[] password;

        private ResolvedCredentials(String username, char[] password) {
            this.username = Objects.requireNonNull(username, "username");
            this.password = Objects.requireNonNull(password, "password").clone();
        }

        public String username() {
            return username;
        }

        public char[] passwordCopy() {
            return password.clone();
        }

        @Override
        public String toString() {
            return "ResolvedCredentials[username=<redacted>, password=<redacted>]";
        }
    }
}
