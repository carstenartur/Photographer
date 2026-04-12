package com.photographer.focusstack.observer.rules;

import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CooldownRuleTest {

    private static TriggerContext ctx(Instant now, Instant lastEvent) {
        return new TriggerContext(now, 0.5, 0.1, RegionOfInterest.fullFrame(), lastEvent, "wildlife", Map.of());
    }

    @Test
    void allowsWhenNoLastEvent() {
        CooldownRule rule = new CooldownRule(30);
        assertTrue(rule.shouldTrigger(ctx(Instant.now(), null)));
    }

    @Test
    void allowsWhenCooldownExpired() {
        CooldownRule rule = new CooldownRule(30);
        Instant now = Instant.now();
        Instant last = now.minusSeconds(60);
        assertTrue(rule.shouldTrigger(ctx(now, last)));
    }

    @Test
    void blocksWhenWithinCooldown() {
        CooldownRule rule = new CooldownRule(30);
        Instant now = Instant.now();
        Instant last = now.minusSeconds(10);
        assertFalse(rule.shouldTrigger(ctx(now, last)));
    }

    @Test
    void exactCooldownBoundaryAllows() {
        CooldownRule rule = new CooldownRule(30);
        Instant now = Instant.now();
        Instant last = now.minusSeconds(30);
        assertTrue(rule.shouldTrigger(ctx(now, last)));
    }
}
