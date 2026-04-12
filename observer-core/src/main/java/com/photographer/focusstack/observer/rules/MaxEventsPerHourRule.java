package com.photographer.focusstack.observer.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rule: limits the number of events triggered within any rolling 60-minute window.
 *
 * <p>State is kept in instance variables; use one instance per observer pipeline.</p>
 */
public class MaxEventsPerHourRule implements ObserverRule {

    private final int maxPerHour;
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicReference<Instant> windowStart = new AtomicReference<>(null);

    public MaxEventsPerHourRule(int maxPerHour) {
        this.maxPerHour = maxPerHour;
    }

    @Override
    public String name() {
        return "MaxEventsPerHourRule(max=" + maxPerHour + ")";
    }

    @Override
    public boolean shouldTrigger(TriggerContext context) {
        Instant now = context.now();
        Instant ws = windowStart.get();
        if (ws == null || Duration.between(ws, now).toHours() >= 1) {
            windowStart.set(now);
            count.set(0);
        }
        return count.get() < maxPerHour;
    }

    /** Must be called after a trigger is accepted to increment the counter. */
    public void recordTrigger() {
        count.incrementAndGet();
    }

    @Override
    public int priority() {
        return 60;
    }
}
