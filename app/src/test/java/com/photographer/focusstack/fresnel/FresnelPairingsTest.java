package com.photographer.focusstack.fresnel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FresnelPairingsTest {
    @TempDir Path directory;
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    FresnelPairings store() {
        return new FresnelPairings(directory, clock);
    }

    @Test void exchangesOnceAndSurvivesRestartWithOnlyTheTokenHash() throws Exception {
        var pairings = store();
        var grant = pairings.issue("https://photographer.example", List.of("fresnel:descriptor:read"));
        var request = new FresnelPairings.PairRequest(grant.pairingCode(), "fresnel", "Lab", "1.0", 1);
        var pairing = pairings.exchange(request, "192.0.2.1");
        assertEquals("PAIRING_CODE_INVALID", assertThrows(FresnelProblem.class,
                () -> pairings.exchange(request, "192.0.2.1")).code());
        String auth = "Bearer " + pairing.bearerToken();
        var restarted = store();
        assertEquals(pairing.pairingId(), restarted.authorize(auth, "fresnel:descriptor:read"));
        assertEquals("INSUFFICIENT_SCOPE", assertThrows(FresnelProblem.class,
                () -> restarted.authorize(auth, "fresnel:media:read")).code());
        assertEquals("AUTHENTICATION_FAILED", assertThrows(FresnelProblem.class,
                () -> restarted.authorize(auth + "x", "fresnel:descriptor:read")).code());
        try (var files = Files.walk(directory)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String disk = Files.readString(f);
                assertFalse(disk.contains(pairing.bearerToken()));
                assertFalse(disk.contains(grant.pairingCode()));
            }
        }
        assertEquals("PAIRING", restarted.list().getFirst().state());
        restarted.recordSuccessfulRequest(pairing.pairingId());
        assertEquals("CONNECTED", restarted.list().getFirst().state());
        restarted.revoke(pairing.pairingId(), () -> {});
        var again = store();
        assertEquals("REVOKED", again.list().getFirst().state());
        assertEquals("AUTHENTICATION_FAILED", assertThrows(FresnelProblem.class,
                () -> again.whilePaired(pairing.pairingId(), () -> fail("revoked"))).code());
    }

    @Test void failedTokensAreThrottledPerPeer() {
        var pairings = store();
        for (int i = 0; i < 16; i++) assertEquals(401, assertThrows(FresnelProblem.class,
                () -> pairings.authenticatePeer("Bearer invalid", "192.0.2.1")).status());
        assertEquals(429, assertThrows(FresnelProblem.class,
                () -> pairings.authenticatePeer("Bearer invalid", "192.0.2.1")).status());
        assertEquals(401, assertThrows(FresnelProblem.class,
                () -> pairings.authenticatePeer("Bearer invalid", "192.0.2.2")).status());
    }

    @Test void incompatibleVersionLeavesCodeAvailableAndRateLimitBoundsFailures() {
        var p = store();
        var code = p.issue("https://photographer.example", List.of("fresnel:descriptor:read")).pairingCode();
        var wrong = new FresnelPairings.PairRequest(code, "client", "Lab", "2.0", 2);
        assertEquals("INCOMPATIBLE", assertThrows(FresnelProblem.class, () -> p.exchange(wrong, "a")).code());
        assertNotNull(p.exchange(new FresnelPairings.PairRequest(code, "client", "Lab", "2.0", 1), "a"));
        var invalid = new FresnelPairings.PairRequest("unknown", "client", "Lab", "2.0", 1);
        for (int i = 0; i < 5; i++) assertEquals(403, assertThrows(FresnelProblem.class,
                () -> p.exchange(invalid, "b")).status());
        assertEquals(429, assertThrows(FresnelProblem.class, () -> p.exchange(invalid, "b")).status());
    }
}
