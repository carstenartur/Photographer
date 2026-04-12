package com.photographer.focusstack.observer.rules;

import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RulesEngineTest {

    private static TriggerContext ctx(double motion, double size, Instant lastEvent) {
        return new TriggerContext(Instant.now(), motion, size, RegionOfInterest.fullFrame(), lastEvent, "wildlife", Map.of());
    }

    @Test
    void allRulesPass() {
        RulesEngine engine = new RulesEngine(List.of(
                new MotionThresholdRule(0.1),
                new MinObjectSizeRule(0.02),
                new CooldownRule(10)
        ));
        TriggerContext ctx = ctx(0.3, 0.1, Instant.now().minusSeconds(60));
        assertTrue(engine.shouldTrigger(ctx));
    }

    @Test
    void oneRuleBlocks() {
        RulesEngine engine = new RulesEngine(List.of(
                new MotionThresholdRule(0.5),   // blocks (motion=0.3 < 0.5)
                new MinObjectSizeRule(0.02)
        ));
        TriggerContext ctx = ctx(0.3, 0.1, null);
        assertFalse(engine.shouldTrigger(ctx));
    }

    @Test
    void emptyRulesListAlwaysAllows() {
        RulesEngine engine = new RulesEngine(List.of());
        assertTrue(engine.shouldTrigger(ctx(0.0, 0.0, null)));
    }

    @Test
    void rulesAreEvaluatedInPriorityOrder() {
        // Higher priority rules should be checked first; the engine should stop at the first failure
        List<String> evaluated = new java.util.ArrayList<>();
        ObserverRule highPriority = new ObserverRule() {
            @Override public String name() { return "high"; }
            @Override public boolean shouldTrigger(TriggerContext c) { evaluated.add("high"); return false; }
            @Override public int priority() { return 100; }
        };
        ObserverRule lowPriority = new ObserverRule() {
            @Override public String name() { return "low"; }
            @Override public boolean shouldTrigger(TriggerContext c) { evaluated.add("low"); return true; }
            @Override public int priority() { return 10; }
        };
        RulesEngine engine = new RulesEngine(List.of(lowPriority, highPriority));
        engine.shouldTrigger(ctx(0.3, 0.1, null));
        assertEquals(List.of("high"), evaluated); // high evaluated first and blocked
    }
}
