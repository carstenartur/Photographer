package com.photographer.focusstack.observer.bird;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Single-owner visit policy. Missing/invalid frames must be reported using {@link #gap()}. */
public final class BirdTriggerPolicy {
    private final BirdTriggerSettings settings;
    private final Duration continuityLimit;
    private Instant lastObservation;
    private Instant absentSince;
    private Instant lastCapture;
    private boolean capturedThisVisit;

    public BirdTriggerPolicy(BirdTriggerSettings settings) {
        this.settings = Objects.requireNonNull(settings);
        continuityLimit = Duration.ofMillis(Math.max(2000L, settings.intervalMs() * 3L));
    }

    public boolean shouldCapture(Instant frameTime, boolean birdPresent) {
        Objects.requireNonNull(frameTime);
        if (lastObservation != null) {
            if (!frameTime.isAfter(lastObservation)) {
                gap();
                return false;
            }
            if (Duration.between(lastObservation, frameTime).compareTo(continuityLimit) > 0) {
                gap();
            }
        }
        lastObservation = frameTime;
        if (!birdPresent) {
            if (absentSince == null) absentSince = frameTime;
            if (Duration.between(absentSince, frameTime).getSeconds() >= settings.absenceSeconds()) {
                capturedThisVisit = false;
            }
            return false;
        }
        absentSince = null;
        if (!capturedThisVisit) return true;
        return settings.repeatSeconds() > 0 && lastCapture != null
                && !frameTime.isBefore(lastCapture.plusSeconds(settings.repeatSeconds()));
    }

    /** Called when a capture is committed, including ambiguous camera failures: never blindly retry it. */
    public void captureCommitted(Instant at) {
        capturedThisVisit = true;
        lastCapture = Objects.requireNonNull(at);
        absentSince = null;
    }

    /** A blackout is not evidence that a bird has left. */
    public void gap() {
        absentSince = null;
    }
}
