package com.photographer.focusstack.common.multiclient;

import java.time.Instant;
import java.util.Objects;

/**
 * Snapshot of one client attached to a shared capture session.
 *
 * <p>Instances are immutable; the {@link MultiClientSessionRegistry} produces
 * a new {@code ClientSession} whenever a role changes so that consumers
 * (event broadcasters, audit log) always see a self-consistent value.</p>
 *
 * @param sessionId   id of the shared capture session
 * @param clientId    stable per-client identifier (typically a UUID set by the browser)
 * @param role        current {@link SessionRole}; never {@code null}
 * @param connectedAt when the client first joined; never {@code null}
 * @param displayName human-readable label shown to other clients (may be {@code null})
 */
public record ClientSession(
        String sessionId,
        String clientId,
        SessionRole role,
        Instant connectedAt,
        String displayName
) {

    public ClientSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(connectedAt, "connectedAt");
    }

    /** Returns a copy of this session with a different role. */
    public ClientSession withRole(SessionRole newRole) {
        return new ClientSession(sessionId, clientId, newRole, connectedAt, displayName);
    }
}
