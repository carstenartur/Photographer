package com.photographer.focusstack.common.multiclient;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory registry of {@link ClientSession}s for the
 * "Companion" multi-client model: several browsers (or REST clients) attach
 * to the same camera session and the registry tracks who is connected, in
 * which role, and how long.
 *
 * <p>The registry is intentionally framework-agnostic — no Spring, no JPA —
 * so it can live in {@code common-api} and be reused by the WebSocket /
 * REST layer in {@code app}. Persistence and event broadcasting are the
 * responsibility of the caller.</p>
 *
 * <p><strong>Role-promotion rules:</strong> the first client to join a session
 * automatically becomes {@link SessionRole#DIRECTOR}; subsequent clients join
 * as {@link SessionRole#OBSERVER} by default. Only a {@code DIRECTOR} may
 * change another client's role (enforced by
 * {@link #changeRole(String, String, String, SessionRole)}).</p>
 */
public class MultiClientSessionRegistry {

    /** sessionId → (clientId → ClientSession). */
    private final ConcurrentMap<String, ConcurrentMap<String, ClientSession>> sessions =
            new ConcurrentHashMap<>();

    private final Clock clock;

    public MultiClientSessionRegistry() {
        this(Clock.systemUTC());
    }

    /** Test-friendly constructor accepting a fixed/controlled clock. */
    public MultiClientSessionRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a new client in the session.
     *
     * <p>If the session is empty the client is promoted to
     * {@link SessionRole#DIRECTOR}; otherwise it joins as
     * {@link SessionRole#OBSERVER}. Re-joining with the same {@code clientId}
     * is idempotent and returns the existing entry unchanged (so re-connects
     * after a brief network drop don't demote a director).</p>
     *
     * @return the {@link ClientSession} now attached to the session
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code sessionId} or {@code clientId} is blank
     */
    public ClientSession join(String sessionId, String clientId, String displayName) {
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(clientId, "clientId");

        ConcurrentMap<String, ClientSession> session =
                sessions.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>());

        return session.compute(clientId, (cid, existing) -> {
            if (existing != null) {
                return existing;
            }
            SessionRole role = session.isEmpty() ? SessionRole.DIRECTOR : SessionRole.OBSERVER;
            return new ClientSession(sessionId, clientId, role, clock.instant(), displayName);
        });
    }

    /**
     * Removes a client from a session.
     *
     * <p>If the leaving client was the last {@link SessionRole#DIRECTOR} and
     * any other clients remain, the longest-connected remaining client is
     * promoted to {@code DIRECTOR} so the session never ends up "headless".</p>
     *
     * @return {@code true} when a client was actually removed
     */
    public boolean leave(String sessionId, String clientId) {
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(clientId, "clientId");

        ConcurrentMap<String, ClientSession> session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        ClientSession removed = session.remove(clientId);
        if (removed == null) {
            return false;
        }
        if (session.isEmpty()) {
            sessions.remove(sessionId, session);
            return true;
        }
        // If we just removed the last director, promote the longest-connected client.
        boolean directorRemains = session.values().stream()
                .anyMatch(c -> c.role() == SessionRole.DIRECTOR);
        if (!directorRemains) {
            session.values().stream()
                    .min((a, b) -> a.connectedAt().compareTo(b.connectedAt()))
                    .ifPresent(oldest -> session.put(oldest.clientId(),
                            oldest.withRole(SessionRole.DIRECTOR)));
        }
        return true;
    }

    /**
     * Changes the role of {@code targetClientId} on behalf of {@code actingClientId}.
     *
     * @throws ClientNotFoundException if either client is not in the session
     * @throws RoleChangeDeniedException if the acting client is not a {@link SessionRole#DIRECTOR}
     * @return the updated {@link ClientSession} of the target client
     */
    public ClientSession changeRole(String sessionId, String actingClientId,
                                    String targetClientId, SessionRole newRole) {
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(actingClientId, "actingClientId");
        requireNonBlank(targetClientId, "targetClientId");
        Objects.requireNonNull(newRole, "newRole");

        ConcurrentMap<String, ClientSession> session = sessions.get(sessionId);
        if (session == null) {
            throw new ClientNotFoundException(sessionId, actingClientId);
        }
        ClientSession actor = session.get(actingClientId);
        if (actor == null) {
            throw new ClientNotFoundException(sessionId, actingClientId);
        }
        if (!actor.role().canManageSession()) {
            throw new RoleChangeDeniedException(actingClientId, actor.role());
        }
        ClientSession updated = session.computeIfPresent(targetClientId,
                (cid, existing) -> existing.withRole(newRole));
        if (updated == null) {
            throw new ClientNotFoundException(sessionId, targetClientId);
        }
        return updated;
    }

    /** Returns the client session entry for {@code (sessionId, clientId)}, if present. */
    public Optional<ClientSession> find(String sessionId, String clientId) {
        ConcurrentMap<String, ClientSession> session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.get(clientId));
    }

    /** Returns an immutable snapshot of all clients currently attached to {@code sessionId}. */
    public List<ClientSession> listClients(String sessionId) {
        ConcurrentMap<String, ClientSession> session = sessions.get(sessionId);
        if (session == null || session.isEmpty()) {
            return List.of();
        }
        return List.copyOf(session.values());
    }

    /** Returns an immutable snapshot of all session ids currently tracked. */
    public List<String> listSessions() {
        return List.copyOf(sessions.keySet());
    }

    /**
     * Convenience helper used by command handlers: throws
     * {@link RoleChangeDeniedException} unless the given client may control
     * the camera (i.e. is a {@link SessionRole#CONTROLLER} or higher).
     */
    public ClientSession requireCanControlCamera(String sessionId, String clientId) {
        ClientSession s = find(sessionId, clientId)
                .orElseThrow(() -> new ClientNotFoundException(sessionId, clientId));
        if (!s.role().canControlCamera()) {
            throw new RoleChangeDeniedException(clientId, s.role());
        }
        return s;
    }

    // ---------------------------------------------------------------------

    private static void requireNonBlank(String s, String name) {
        Objects.requireNonNull(s, name);
        if (s.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** Test-only: clears all state. */
    void clear() {
        sessions.clear();
    }

    // ---- exceptions -----------------------------------------------------

    /** Thrown when an operation references a client/session that is not registered. */
    public static class ClientNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ClientNotFoundException(String sessionId, String clientId) {
            super("Client '" + clientId + "' not found in session '" + sessionId + "'");
        }
    }

    /**
     * Thrown when a client tries to perform an action that its current
     * {@link SessionRole} does not permit (role change, camera command from
     * an observer, …).
     */
    public static class RoleChangeDeniedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final SessionRole currentRole;
        public RoleChangeDeniedException(String clientId, SessionRole currentRole) {
            super("Client '" + clientId + "' (" + currentRole
                    + ") is not permitted to perform this action");
            this.currentRole = currentRole;
        }
        public SessionRole currentRole() { return currentRole; }
    }

    /** Returns Collections.emptyMap()-like snapshot — for diagnostics only. */
    public java.util.Map<String, java.util.List<ClientSession>> snapshot() {
        java.util.Map<String, java.util.List<ClientSession>> out = new java.util.LinkedHashMap<>();
        sessions.forEach((sid, m) -> out.put(sid, List.copyOf(m.values())));
        return Collections.unmodifiableMap(out);
    }
}
