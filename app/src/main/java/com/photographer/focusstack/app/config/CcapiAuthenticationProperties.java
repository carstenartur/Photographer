package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * External-only Canon CCAPI account configuration.
 *
 * <p>Entries are keyed by the exact camera profile id. They are deliberately not
 * part of {@code CameraProfile}, persistence entities or public REST models.</p>
 */
@ConfigurationProperties(prefix = "focusstack.camera.ccapi")
public final class CcapiAuthenticationProperties {

    private Map<String, Credential> credentials = new LinkedHashMap<>();
    private final AtomicLong configurationRevision = new AtomicLong();

    public synchronized Map<String, Credential> getCredentials() {
        return credentials;
    }

    public synchronized void setCredentials(Map<String, Credential> credentials) {
        this.credentials = credentials == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(credentials);
        configurationRevision.incrementAndGet();
    }

    /** Resolves an exact profile-id entry and rejects incomplete secret configuration. */
    public Optional<ResolvedCredentials> resolve(String profileId) {
        CredentialSnapshot snapshot = snapshot(profileId);
        return snapshot.credentials();
    }

    /**
     * Returns a secret-free state suitable for cache identity decisions.
     *
     * <p>The revisions are in-memory change counters, not password hashes. A map
     * replacement increments the configuration revision; mutating an existing
     * credential through its binding setters increments the entry revision.</p>
     */
    public CredentialState state(String profileId) {
        return inspect(profileId, false).state();
    }

    /** Returns one internally consistent credential and revision snapshot. */
    public CredentialSnapshot snapshot(String profileId) {
        Inspection inspection = inspect(profileId, true);
        return new CredentialSnapshot(inspection.state(), inspection.credentials());
    }

    private Inspection inspect(String profileId, boolean includeCredentials) {
        Objects.requireNonNull(profileId, "profileId");
        Credential configured;
        long mapRevision;
        synchronized (this) {
            configured = credentials.get(profileId);
            mapRevision = configurationRevision.get();
        }
        if (configured == null) {
            return new Inspection(
                    new CredentialState(false, mapRevision, 0L),
                    Optional.empty());
        }
        return configured.inspect(profileId, mapRevision, includeCredentials);
    }

    /** Secret-free identity of the effective credential configuration. */
    public record CredentialState(
            boolean credentialsConfigured,
            long configurationRevision,
            long credentialRevision) {
    }

    /** Mutable binding target. Its string representation never exposes secret values. */
    public static final class Credential {
        private String username;
        private String password;
        private long revision;

        public synchronized String getUsername() {
            return username;
        }

        public synchronized void setUsername(String username) {
            if (!Objects.equals(this.username, username)) {
                this.username = username;
                revision++;
            }
        }

        public synchronized String getPassword() {
            return password;
        }

        public synchronized void setPassword(String password) {
            if (!Objects.equals(this.password, password)) {
                this.password = password;
                revision++;
            }
        }

        private synchronized Inspection inspect(
                String profileId,
                long mapRevision,
                boolean includeCredentials) {
            boolean usernamePresent = username != null && !username.isBlank();
            boolean passwordPresent = password != null && !password.isBlank();
            if (usernamePresent != passwordPresent) {
                throw new IllegalStateException(
                        "Incomplete CCAPI credentials configured for camera profile '"
                                + profileId + "'");
            }
            boolean configured = usernamePresent;
            CredentialState state = new CredentialState(configured, mapRevision, revision);
            Optional<ResolvedCredentials> resolved = includeCredentials && configured
                    ? Optional.of(new ResolvedCredentials(username, password.toCharArray()))
                    : Optional.empty();
            return new Inspection(state, resolved);
        }

        @Override
        public synchronized String toString() {
            return "Credential[usernameConfigured="
                    + (username != null && !username.isBlank())
                    + ", password=<redacted>]";
        }
    }

    /** Immutable transport value with defensive password copying and redacted text output. */
    public static final class ResolvedCredentials implements AutoCloseable {
        private final String username;
        private final char[] password;
        private boolean closed;

        private ResolvedCredentials(String username, char[] password) {
            this.username = Objects.requireNonNull(username, "username");
            this.password = Objects.requireNonNull(password, "password").clone();
        }

        public String username() {
            return username;
        }

        public synchronized char[] passwordCopy() {
            if (closed) {
                throw new IllegalStateException("Resolved CCAPI credentials are closed");
            }
            return password.clone();
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                Arrays.fill(password, '\0');
                closed = true;
            }
        }

        @Override
        public String toString() {
            return "ResolvedCredentials[username=<redacted>, password=<redacted>]";
        }
    }

    /** Atomic credentials/state snapshot used while constructing one transport. */
    public static final class CredentialSnapshot implements AutoCloseable {
        private final CredentialState state;
        private final Optional<ResolvedCredentials> credentials;

        private CredentialSnapshot(
                CredentialState state,
                Optional<ResolvedCredentials> credentials) {
            this.state = Objects.requireNonNull(state, "state");
            this.credentials = Objects.requireNonNull(credentials, "credentials");
        }

        public CredentialState state() {
            return state;
        }

        public Optional<ResolvedCredentials> credentials() {
            return credentials;
        }

        @Override
        public void close() {
            credentials.ifPresent(ResolvedCredentials::close);
        }

        @Override
        public String toString() {
            return "CredentialSnapshot[state=" + state + ", credentials=<redacted>]";
        }
    }

    private record Inspection(
            CredentialState state,
            Optional<ResolvedCredentials> credentials) {
    }
}
