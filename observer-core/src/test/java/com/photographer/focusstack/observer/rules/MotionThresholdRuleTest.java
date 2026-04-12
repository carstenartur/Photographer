package com.photographer.focusstack.observer.rules;

import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MotionThresholdRuleTest {

    private static TriggerContext ctx(double motionScore) {
        return new TriggerContext(Instant.now(), motionScore, 0.1, RegionOfInterest.fullFrame(), null, "wildlife", Map.of());
    }

    @Test
    void allowsWhenAboveThreshold() {
        MotionThresholdRule rule = new MotionThresholdRule(0.15);
        assertTrue(rule.shouldTrigger(ctx(0.3)));
    }

    @Test
    void blocksWhenBelowThreshold() {
        MotionThresholdRule rule = new MotionThresholdRule(0.15);
        assertFalse(rule.shouldTrigger(ctx(0.05)));
    }

    @Test
    void allowsAtExactThreshold() {
        MotionThresholdRule rule = new MotionThresholdRule(0.15);
        assertTrue(rule.shouldTrigger(ctx(0.15)));
    }
}
