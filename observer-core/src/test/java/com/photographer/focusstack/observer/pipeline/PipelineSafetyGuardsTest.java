package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.common.pipeline.PipelineBlockerKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PipelineSafetyGuardsTest {

    /** A clock whose {@code now} can be advanced manually for deterministic testing. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Instant instant() { return now; }
    }

    // ---- emergency stop ----

    @Test
    void emergencyStop_blocksAction_andLogsEvent() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(0);
        g.requestEmergencyStop();

        assertTrue(g.isEmergencyStop());
        Optional<PipelineBlockerKind> blocker = g.checkBeforeAction(true);
        assertTrue(blocker.isPresent());
        assertEquals(PipelineBlockerKind.EMERGENCY_STOP, blocker.get());

        // Two events: stop request + blocked action
        assertTrue(g.getEventLog().stream()
                .anyMatch(e -> e.blocker() == PipelineBlockerKind.EMERGENCY_STOP));
    }

    @Test
    void clearEmergencyStop_unblocksAction() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(0);
        g.requestEmergencyStop();
        g.clearEmergencyStop();

        assertFalse(g.isEmergencyStop());
        assertTrue(g.checkBeforeAction(true).isEmpty());
    }

    // ---- not connected gate ----

    @Test
    void notConnected_blocksWithNotConnectedKind() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(0);
        Optional<PipelineBlockerKind> blocker = g.checkBeforeAction(false);
        assertTrue(blocker.isPresent());
        assertEquals(PipelineBlockerKind.NOT_CONNECTED, blocker.get());
    }

    // ---- rate limit (60 s sliding window) ----

    @Test
    void rateLimit_disabledWhenZero() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(0);
        for (int i = 0; i < 1000; i++) {
            g.recordCapture();
        }
        assertTrue(g.checkBeforeAction(true).isEmpty());
    }

    @Test
    void rateLimit_blocksWhenExceeded() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PipelineSafetyGuards g = new PipelineSafetyGuards(3, 100, clock);

        // Three captures within the window
        g.recordCapture();
        g.recordCapture();
        g.recordCapture();

        Optional<PipelineBlockerKind> blocker = g.checkBeforeAction(true);
        assertTrue(blocker.isPresent());
        assertEquals(PipelineBlockerKind.RATE_LIMITED, blocker.get());
        assertEquals(3, g.capturesInLastMinute());
    }

    @Test
    void rateLimit_oldCapturesEvictedAfter60Seconds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PipelineSafetyGuards g = new PipelineSafetyGuards(2, 100, clock);

        g.recordCapture();
        g.recordCapture();
        assertTrue(g.checkBeforeAction(true).isPresent(), "should be rate limited");

        // Advance past the 60-second window
        clock.advanceSeconds(61);

        assertTrue(g.checkBeforeAction(true).isEmpty(), "old captures should be evicted");
        assertEquals(0, g.capturesInLastMinute());
    }

    @Test
    void rateLimit_belowThresholdAllowsAction() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(5);
        g.recordCapture();
        g.recordCapture();
        assertTrue(g.checkBeforeAction(true).isEmpty());
        assertEquals(2, g.capturesInLastMinute());
    }

    // ---- event log capacity ----

    @Test
    void eventLog_cappedAtCapacity() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(0, 5, Clock.systemUTC());
        for (int i = 0; i < 20; i++) {
            g.recordEvent(PipelineEvent.observe("evt-" + i));
        }
        List<PipelineEvent> log = g.getEventLog();
        assertEquals(5, log.size());
        // Oldest evicted; newest preserved
        assertEquals("evt-15", log.get(0).message());
        assertEquals("evt-19", log.get(4).message());
    }

    @Test
    void eventLog_returnsDefensiveCopy() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(0);
        g.recordEvent(PipelineEvent.observe("e1"));
        List<PipelineEvent> snapshot = g.getEventLog();
        snapshot.clear();
        assertEquals(1, g.getEventLog().size(), "internal log must not be affected by external mutation");
    }

    @Test
    void recordEvent_nullIgnored() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(0);
        g.recordEvent(null);
        assertTrue(g.getEventLog().isEmpty());
    }

    @Test
    void defaultCapacityIs100() {
        PipelineSafetyGuards g = new PipelineSafetyGuards(0);
        assertEquals(100, g.getEventLogCapacity());
        assertEquals(PipelineSafetyGuards.DEFAULT_EVENT_LOG_CAPACITY, g.getEventLogCapacity());
    }

    @Test
    void constructor_invalidCapacityThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineSafetyGuards(0, 0, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineSafetyGuards(0, -1, Clock.systemUTC()));
    }
}
