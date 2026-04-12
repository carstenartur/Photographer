package com.photographer.focusstack.observer.rules;

import java.time.Duration;

/**
 * Rule: enforces a minimum wait time between consecutive observation events.
 */
public class CooldownRule implements ObserverRule {

    private final int cooldownSeconds;

    public CooldownRule(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    @Override
    public String name() {
        return "CooldownRule(cooldown=" + cooldownSeconds + "s)";
    }

    @Override
    public boolean shouldTrigger(TriggerContext context) {
        if (context.lastEventTime() == null) {
            return true;
        }
        Duration elapsed = Duration.between(context.lastEventTime(), context.now());
        return elapsed.toSeconds() >= cooldownSeconds;
    }

    @Override
    public int priority() {
        return 100;
    }
}
