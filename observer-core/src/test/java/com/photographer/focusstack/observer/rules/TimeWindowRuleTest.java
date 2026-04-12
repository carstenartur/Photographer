package com.photographer.focusstack.observer.rules;

import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TimeWindowRuleTest {

    private static TriggerContext ctxAt(int hour, int minute) {
        ZoneId utc = ZoneId.of("UTC");
        Instant now = ZonedDateTime.now(utc).withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant();
        return new TriggerContext(now, 0.5, 0.1, RegionOfInterest.fullFrame(), null, "wildlife", Map.of());
    }

    @Test
    void allowsDuringWindow() {
        TimeWindowRule rule = new TimeWindowRule("06:00", "21:00", ZoneId.of("UTC"));
        assertTrue(rule.shouldTrigger(ctxAt(12, 0)));
    }

    @Test
    void blocksBeforeWindow() {
        TimeWindowRule rule = new TimeWindowRule("06:00", "21:00", ZoneId.of("UTC"));
        assertFalse(rule.shouldTrigger(ctxAt(3, 0)));
    }

    @Test
    void blocksAfterWindow() {
        TimeWindowRule rule = new TimeWindowRule("06:00", "21:00", ZoneId.of("UTC"));
        assertFalse(rule.shouldTrigger(ctxAt(22, 0)));
    }

    @Test
    void allowsAtWindowStart() {
        TimeWindowRule rule = new TimeWindowRule("06:00", "21:00", ZoneId.of("UTC"));
        assertTrue(rule.shouldTrigger(ctxAt(6, 0)));
    }

    @Test
    void allowsAtWindowEnd() {
        TimeWindowRule rule = new TimeWindowRule("06:00", "21:00", ZoneId.of("UTC"));
        assertTrue(rule.shouldTrigger(ctxAt(21, 0)));
    }
}
