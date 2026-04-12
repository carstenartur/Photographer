package com.photographer.focusstack.observer.rules;

import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MinObjectSizeRuleTest {

    private static TriggerContext ctx(double objectSize) {
        return new TriggerContext(Instant.now(), 0.5, objectSize, RegionOfInterest.fullFrame(), null, "wildlife", Map.of());
    }

    @Test
    void allowsWhenAboveMinSize() {
        MinObjectSizeRule rule = new MinObjectSizeRule(0.05);
        assertTrue(rule.shouldTrigger(ctx(0.1)));
    }

    @Test
    void blocksWhenBelowMinSize() {
        MinObjectSizeRule rule = new MinObjectSizeRule(0.05);
        assertFalse(rule.shouldTrigger(ctx(0.01)));
    }

    @Test
    void allowsAtExactMinSize() {
        MinObjectSizeRule rule = new MinObjectSizeRule(0.05);
        assertTrue(rule.shouldTrigger(ctx(0.05)));
    }
}
