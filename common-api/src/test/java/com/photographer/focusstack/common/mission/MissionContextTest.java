package com.photographer.focusstack.common.mission;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class MissionContextTest {

    @Test
    void nowOnly_populatesClockSourceOnly() {
        MissionContext c = MissionContext.nowOnly();
        assertNotNull(c.instant());
        assertTrue(c.has(ContextSource.CLOCK));
        assertFalse(c.has(ContextSource.GPS));
        assertEquals(1, c.populatedSources().size());
    }

    @Test
    void builder_collectsPopulatedSources() {
        MissionContext c = MissionContext.builder(Instant.parse("2026-01-01T00:00:00Z"))
                .gps(48.137, 11.575, 520.0)
                .moonPhase(0.5)
                .windKph(12.0)
                .build();
        assertTrue(c.has(ContextSource.GPS));
        assertTrue(c.has(ContextSource.MOON));
        assertTrue(c.has(ContextSource.WIND));
        assertTrue(c.has(ContextSource.CLOCK));
        assertFalse(c.has(ContextSource.WEATHER));
        assertEquals(48.137, c.latitude(), 1e-6);
        assertEquals(0.5, c.moonPhase(), 1e-6);
    }

    @Test
    void invalidMoonPhase_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MissionContext.builder().moonPhase(1.5).build());
        assertThrows(IllegalArgumentException.class,
                () -> MissionContext.builder().moonPhase(-0.1).build());
    }

    @Test
    void populatedSources_isImmutable() {
        MissionContext c = MissionContext.nowOnly();
        assertThrows(UnsupportedOperationException.class,
                () -> c.populatedSources().add(ContextSource.GPS));
    }

    @Test
    void populatedSources_defensiveCopyAtConstruction() {
        EnumSet<ContextSource> mutable = EnumSet.of(ContextSource.CLOCK);
        MissionContext c = new MissionContext(Instant.now(), null, null, null, null, null,
                null, null, null, mutable);
        mutable.add(ContextSource.GPS);
        assertFalse(c.has(ContextSource.GPS), "external mutation must not leak in");
    }

    @Test
    void nullInstant_rejected() {
        assertThrows(NullPointerException.class,
                () -> new MissionContext(null, null, null, null, null, null, null, null, null,
                        EnumSet.noneOf(ContextSource.class)));
    }
}
