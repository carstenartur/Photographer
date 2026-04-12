package com.photographer.focusstack.observer.rules;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Rule: only trigger within a configured time window (e.g. daylight hours).
 */
public class TimeWindowRule implements ObserverRule {

    private final LocalTime start;
    private final LocalTime end;
    private final ZoneId zone;

    public TimeWindowRule(String startTime, String endTime, ZoneId zone) {
        this.start = LocalTime.parse(startTime);
        this.end = LocalTime.parse(endTime);
        this.zone = zone;
    }

    public TimeWindowRule(String startTime, String endTime) {
        this(startTime, endTime, ZoneId.systemDefault());
    }

    @Override
    public String name() {
        return "TimeWindowRule(%s–%s)".formatted(start, end);
    }

    @Override
    public boolean shouldTrigger(TriggerContext context) {
        LocalTime now = context.now().atZone(zone).toLocalTime();
        if (start.isBefore(end)) {
            return !now.isBefore(start) && !now.isAfter(end);
        }
        // Overnight window (e.g. 22:00–06:00)
        return !now.isBefore(start) || !now.isAfter(end);
    }

    @Override
    public int priority() {
        return 90;
    }
}
