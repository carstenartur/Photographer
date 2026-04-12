package com.photographer.focusstack.observer.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * Evaluates a list of {@link ObserverRule}s in priority order.
 * All rules must allow the trigger for the result to be {@code true}.
 */
public class RulesEngine {

    private static final Logger log = LoggerFactory.getLogger(RulesEngine.class);

    private final List<ObserverRule> rules;

    public RulesEngine(List<ObserverRule> rules) {
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt(ObserverRule::priority).reversed())
                .toList();
    }

    /**
     * Returns {@code true} if all rules allow triggering for the given context.
     */
    public boolean shouldTrigger(TriggerContext context) {
        for (ObserverRule rule : rules) {
            if (!rule.shouldTrigger(context)) {
                log.debug("Rule blocked trigger: {}", rule.name());
                return false;
            }
        }
        return true;
    }
}
