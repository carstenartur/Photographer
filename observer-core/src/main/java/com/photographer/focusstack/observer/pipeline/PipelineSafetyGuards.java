package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.common.pipeline.PipelineBlockerKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Production-safety guards for the Observe → Decide → Act pipeline.
 *
 * <p>This service centralises the safety checks that protect long-running
 * wildlife and astro sessions from cascading failures, hardware abuse and
 * runaway capture loops:</p>
 * <ul>
 *   <li>An {@code emergencyStop} flag that any UI or REST endpoint can flip
 *       at any time to immediately halt the pipeline.</li>
 *   <li>A hard rate limit ({@code maxCapturesPerMinute}) using a 60-second
 *       sliding window, preventing the pipeline from stressing the camera
 *       beyond its sustained-capture rating.</li>
 *   <li>A connection-state gate that blocks the pipeline whenever the camera
 *       reports {@code connected = false}.</li>
 *   <li>A capped event log (default 100 entries) recording every observe,
 *       decide, act and blocker for forensic inspection from the UI.</li>
 * </ul>
 *
 * <p>Thread safety: all mutating methods are {@code synchronized}. Read-only
 * accessors return defensive copies and are safe to call concurrently.</p>
 */
public class PipelineSafetyGuards {

    private static final Logger log = LoggerFactory.getLogger(PipelineSafetyGuards.class);

    /** Default maximum number of {@link PipelineEvent}s retained in the log. */
    public static final int DEFAULT_EVENT_LOG_CAPACITY = 100;

    private final Clock clock;
    private final int maxCapturesPerMinute;
    private final int eventLogCapacity;

    private final Deque<Instant> captureTimestamps = new ArrayDeque<>();
    private final Deque<PipelineEvent> eventLog = new ArrayDeque<>();

    private volatile boolean emergencyStop = false;

    /**
     * Creates a guards instance with default capacity ({@value #DEFAULT_EVENT_LOG_CAPACITY})
     * and the system clock.
     *
     * @param maxCapturesPerMinute hard upper bound on captures per 60-second window;
     *                             use {@code 0} or a negative value to disable the limit
     */
    public PipelineSafetyGuards(int maxCapturesPerMinute) {
        this(maxCapturesPerMinute, DEFAULT_EVENT_LOG_CAPACITY, Clock.systemUTC());
    }

    /**
     * Creates a fully configured guards instance. The custom {@link Clock} makes
     * the rate limit deterministically testable.
     */
    public PipelineSafetyGuards(int maxCapturesPerMinute, int eventLogCapacity, Clock clock) {
        if (eventLogCapacity <= 0) {
            throw new IllegalArgumentException("eventLogCapacity must be > 0");
        }
        this.maxCapturesPerMinute = maxCapturesPerMinute;
        this.eventLogCapacity = eventLogCapacity;
        this.clock = clock;
    }

    // ---- emergency stop ----

    /**
     * Triggers the emergency stop. The pipeline will refuse to take new actions
     * until {@link #clearEmergencyStop()} is called.
     */
    public synchronized void requestEmergencyStop() {
        if (!emergencyStop) {
            emergencyStop = true;
            recordEvent(PipelineEvent.blocked(PipelineBlockerKind.EMERGENCY_STOP,
                    "Emergency stop requested"));
            log.warn("PipelineSafetyGuards: emergency stop activated");
        }
    }

    /** Clears a previously triggered emergency stop. */
    public synchronized void clearEmergencyStop() {
        if (emergencyStop) {
            emergencyStop = false;
            recordEvent(PipelineEvent.observe("Emergency stop cleared"));
            log.info("PipelineSafetyGuards: emergency stop cleared");
        }
    }

    /** Returns {@code true} when the emergency stop is currently active. */
    public boolean isEmergencyStop() {
        return emergencyStop;
    }

    // ---- pre-action gate ----

    /**
     * Checks whether the pipeline may proceed with the next action.
     *
     * <p>Returns an {@link Optional} containing the blocker kind if the action
     * must be refused, or an empty {@code Optional} when the pipeline is free
     * to continue.</p>
     *
     * <p>This method also advances the rate-limit window, but does <em>not</em>
     * record a successful capture — call {@link #recordCapture()} after a
     * capture has actually happened.</p>
     *
     * @param connected current connection state of the camera
     * @return blocker kind if the action is refused; empty if allowed
     */
    public synchronized Optional<PipelineBlockerKind> checkBeforeAction(boolean connected) {
        if (emergencyStop) {
            recordEvent(PipelineEvent.blocked(PipelineBlockerKind.EMERGENCY_STOP,
                    "Action blocked: emergency stop active"));
            return Optional.of(PipelineBlockerKind.EMERGENCY_STOP);
        }
        if (!connected) {
            recordEvent(PipelineEvent.blocked(PipelineBlockerKind.NOT_CONNECTED,
                    "Action blocked: camera not connected"));
            return Optional.of(PipelineBlockerKind.NOT_CONNECTED);
        }
        if (isRateLimited()) {
            recordEvent(PipelineEvent.blocked(PipelineBlockerKind.RATE_LIMITED,
                    "Action blocked: rate limit (" + maxCapturesPerMinute
                            + " captures / 60 s) reached"));
            return Optional.of(PipelineBlockerKind.RATE_LIMITED);
        }
        return Optional.empty();
    }

    /**
     * Records that a capture happened. Call exactly once per successful capture
     * so that the rate-limit window stays accurate.
     */
    public synchronized void recordCapture() {
        Instant now = clock.instant();
        captureTimestamps.addLast(now);
        evictOldCaptures(now);
    }

    /** Returns the number of captures recorded inside the current 60-second window. */
    public synchronized int capturesInLastMinute() {
        evictOldCaptures(clock.instant());
        return captureTimestamps.size();
    }

    private boolean isRateLimited() {
        if (maxCapturesPerMinute <= 0) {
            return false;
        }
        evictOldCaptures(clock.instant());
        return captureTimestamps.size() >= maxCapturesPerMinute;
    }

    private void evictOldCaptures(Instant now) {
        Instant cutoff = now.minusSeconds(60);
        while (!captureTimestamps.isEmpty() && captureTimestamps.peekFirst().isBefore(cutoff)) {
            captureTimestamps.pollFirst();
        }
    }

    // ---- event log ----

    /** Appends an event to the capped log, evicting the oldest entry if necessary. */
    public synchronized void recordEvent(PipelineEvent event) {
        if (event == null) {
            return;
        }
        eventLog.addLast(event);
        while (eventLog.size() > eventLogCapacity) {
            eventLog.pollFirst();
        }
    }

    /** Returns a defensive copy of the event log in chronological order. */
    public synchronized List<PipelineEvent> getEventLog() {
        return new ArrayList<>(eventLog);
    }

    /** Returns the configured maximum capture rate (per 60-second window). */
    public int getMaxCapturesPerMinute() {
        return maxCapturesPerMinute;
    }

    /** Returns the configured event-log capacity. */
    public int getEventLogCapacity() {
        return eventLogCapacity;
    }
}
