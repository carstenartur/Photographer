package com.photographer.focusstack.observer.rules;

/**
 * A single rule that decides whether an observation event should be triggered.
 */
public interface ObserverRule {
    /** Human-readable rule name for logging and diagnostics. */
    String name();

    /** Returns {@code true} if this rule allows the trigger. */
    boolean shouldTrigger(TriggerContext context);

    /**
     * Evaluation priority. Higher values are evaluated first.
     * Rules with lower cost (e.g. simple threshold checks) should have higher priority.
     */
    int priority();
}
