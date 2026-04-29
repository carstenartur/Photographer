package com.photographer.focusstack.common.multiclient;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiClientSessionRegistryTest {

    private final MultiClientSessionRegistry registry = new MultiClientSessionRegistry();

    @Test
    void firstClient_becomesDirector() {
        ClientSession s = registry.join("S1", "alice", "Alice");
        assertEquals(SessionRole.DIRECTOR, s.role());
        assertEquals("S1", s.sessionId());
        assertEquals("alice", s.clientId());
        assertEquals("Alice", s.displayName());
    }

    @Test
    void subsequentClients_joinAsObserver() {
        registry.join("S1", "alice", "Alice");
        ClientSession bob = registry.join("S1", "bob", "Bob");
        assertEquals(SessionRole.OBSERVER, bob.role());
    }

    @Test
    void rejoin_isIdempotent_keepsRole() {
        registry.join("S1", "alice", null);
        // Promote bob, then rejoin bob -> must still be controller
        registry.join("S1", "bob", null);
        registry.changeRole("S1", "alice", "bob", SessionRole.CONTROLLER);
        ClientSession again = registry.join("S1", "bob", null);
        assertEquals(SessionRole.CONTROLLER, again.role(),
                "re-join must not demote an existing client");
    }

    @Test
    void leave_removesClient() {
        registry.join("S1", "alice", null);
        registry.join("S1", "bob", null);
        assertTrue(registry.leave("S1", "bob"));
        assertEquals(1, registry.listClients("S1").size());
        assertFalse(registry.leave("S1", "bob"), "leave is idempotent");
    }

    @Test
    void leavingLastDirector_promotesOldestRemaining() throws InterruptedException {
        // Use natural wall-clock with small sleeps so connectedAt ordering is deterministic.
        registry.join("S1", "alice", null);
        Thread.sleep(2);
        registry.join("S1", "bob", null);
        Thread.sleep(2);
        registry.join("S1", "carol", null);
        assertTrue(registry.leave("S1", "alice"));
        // After alice (only director) leaves, oldest remaining (bob) should be promoted.
        ClientSession bob = registry.find("S1", "bob").orElseThrow();
        assertEquals(SessionRole.DIRECTOR, bob.role());
    }

    @Test
    void leavingLastClient_removesEmptySession() {
        registry.join("S1", "alice", null);
        assertTrue(registry.leave("S1", "alice"));
        assertFalse(registry.listSessions().contains("S1"));
    }

    @Test
    void changeRole_byNonDirector_isRejected() {
        registry.join("S1", "alice", null);            // director
        registry.join("S1", "bob", null);              // observer
        registry.join("S1", "carol", null);            // observer
        // Promote bob to controller (alice can do it)
        registry.changeRole("S1", "alice", "bob", SessionRole.CONTROLLER);
        // bob is now controller but not director -> may not change carol's role
        assertThrows(MultiClientSessionRegistry.RoleChangeDeniedException.class,
                () -> registry.changeRole("S1", "bob", "carol", SessionRole.CONTROLLER));
    }

    @Test
    void changeRole_unknownClient_throws() {
        registry.join("S1", "alice", null);
        assertThrows(MultiClientSessionRegistry.ClientNotFoundException.class,
                () -> registry.changeRole("S1", "alice", "ghost", SessionRole.CONTROLLER));
        assertThrows(MultiClientSessionRegistry.ClientNotFoundException.class,
                () -> registry.changeRole("S1", "ghost", "alice", SessionRole.CONTROLLER));
        assertThrows(MultiClientSessionRegistry.ClientNotFoundException.class,
                () -> registry.changeRole("S99", "alice", "alice", SessionRole.CONTROLLER));
    }

    @Test
    void requireCanControlCamera_throwsForObserver() {
        registry.join("S1", "alice", null);
        registry.join("S1", "bob", null);
        // alice (director) is fine
        assertDoesNotThrow(() -> registry.requireCanControlCamera("S1", "alice"));
        // bob (observer) is not
        assertThrows(MultiClientSessionRegistry.RoleChangeDeniedException.class,
                () -> registry.requireCanControlCamera("S1", "bob"));
    }

    @Test
    void requireCanControlCamera_unknown_throwsClientNotFound() {
        assertThrows(MultiClientSessionRegistry.ClientNotFoundException.class,
                () -> registry.requireCanControlCamera("S1", "ghost"));
    }

    @Test
    void listClients_isImmutable() {
        registry.join("S1", "alice", null);
        List<ClientSession> snapshot = registry.listClients("S1");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(snapshot.get(0)));
    }

    @Test
    void blankIds_rejected() {
        assertThrows(IllegalArgumentException.class, () -> registry.join("", "x", null));
        assertThrows(IllegalArgumentException.class, () -> registry.join(" ", "x", null));
        assertThrows(IllegalArgumentException.class, () -> registry.join("S1", "", null));
        assertThrows(NullPointerException.class, () -> registry.join(null, "x", null));
    }

    @Test
    void clientSession_withRole_returnsCopy() {
        ClientSession a = new ClientSession("S1", "alice", SessionRole.OBSERVER,
                Instant.parse("2026-01-01T00:00:00Z"), "Alice");
        ClientSession b = a.withRole(SessionRole.DIRECTOR);
        assertEquals(SessionRole.OBSERVER, a.role(), "original must not mutate");
        assertEquals(SessionRole.DIRECTOR, b.role());
        assertEquals(a.sessionId(), b.sessionId());
        assertEquals(a.connectedAt(), b.connectedAt());
    }
}
