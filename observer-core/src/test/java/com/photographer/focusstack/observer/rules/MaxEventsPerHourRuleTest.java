package com.photographer.focusstack.observer.rules;

import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaxEventsPerHourRuleTest {

    private static TriggerContext ctx() {
        return new TriggerContext(Instant.now(), 0.5, 0.1, RegionOfInterest.fullFrame(),
                null, "wildlife", Map.of());
    }

    @Test
    void allowsWhenUnderLimit() {
        MaxEventsPerHourRule rule = new MaxEventsPerHourRule(5);
        assertTrue(rule.shouldTrigger(ctx()));
    }

    @Test
    void blocksWhenAtLimit() {
        MaxEventsPerHourRule rule = new MaxEventsPerHourRule(2);
        assertTrue(rule.shouldTrigger(ctx())); // initializes the window
        rule.recordTrigger();
        assertTrue(rule.shouldTrigger(ctx())); // still under limit
        rule.recordTrigger();
        assertFalse(rule.shouldTrigger(ctx())); // now at limit
    }

    @Test
    void resetsAfterHourWindow() {
        MaxEventsPerHourRule rule = new MaxEventsPerHourRule(1);
        assertTrue(rule.shouldTrigger(ctx())); // initializes the window
        rule.recordTrigger();
        assertFalse(rule.shouldTrigger(ctx())); // at limit

        // Simulate time passing beyond 1 hour by calling with a future timestamp
        Instant future = Instant.now().plusSeconds(3601);
        TriggerContext futureCtx = new TriggerContext(future, 0.5, 0.1,
                RegionOfInterest.fullFrame(), null, "wildlife", Map.of());
        assertTrue(rule.shouldTrigger(futureCtx));
    }

    @Test
    void nameContainsLimit() {
        MaxEventsPerHourRule rule = new MaxEventsPerHourRule(10);
        assertTrue(rule.name().contains("10"));
    }

    @Test
    void priorityIsPositive() {
        MaxEventsPerHourRule rule = new MaxEventsPerHourRule(5);
        assertTrue(rule.priority() > 0);
    }
}
