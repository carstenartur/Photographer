package com.photographer.focusstack.observer.rules;

import com.photographer.focusstack.observer.events.RegionOfInterest;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable context object passed to every {@link ObserverRule} during evaluation.
 */
public record TriggerContext(
        Instant now,
        double motionScore,
        double objectSize,
        RegionOfInterest roi,
        Instant lastEventTime,
        String mode,
        Map<String, Object> extra
) {}
