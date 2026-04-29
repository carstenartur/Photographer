package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.common.pipeline.PipelineBlockerKind;

import java.time.Instant;

/**
 * A timestamped entry in the observer pipeline event log.
 *
 * <p>Every step of the Observe → Decide → Act pipeline appends a
 * {@code PipelineEvent} so that operators can inspect the recent history of
 * the pipeline from the UI or logs.</p>
 *
 * @param timestamp when the event occurred
 * @param phase     coarse phase the event belongs to
 * @param blocker   non-{@code null} when the event represents a block; otherwise {@code null}
 * @param message   human-readable description (never {@code null})
 */
public record PipelineEvent(
        Instant timestamp,
        Phase phase,
        PipelineBlockerKind blocker,
        String message
) {

    public PipelineEvent {
        java.util.Objects.requireNonNull(timestamp, "timestamp");
        java.util.Objects.requireNonNull(phase, "phase");
        java.util.Objects.requireNonNull(message, "message");
    }

    /** Coarse pipeline phase, mirroring the Observe → Decide → Act loop. */
    public enum Phase {
        /** A preview frame was inspected. */
        OBSERVE,
        /** A decision was made (trigger fired / suppressed). */
        DECIDE,
        /** An action ran (capture / AI analysis / completion). */
        ACT,
        /** A blocker prevented progress (rate limit, emergency stop, etc.). */
        BLOCKED
    }

    /** Convenience factory for an {@link Phase#OBSERVE} event. */
    public static PipelineEvent observe(String message) {
        return new PipelineEvent(Instant.now(), Phase.OBSERVE, null, message);
    }

    /** Convenience factory for a {@link Phase#DECIDE} event. */
    public static PipelineEvent decide(String message) {
        return new PipelineEvent(Instant.now(), Phase.DECIDE, null, message);
    }

    /** Convenience factory for an {@link Phase#ACT} event. */
    public static PipelineEvent act(String message) {
        return new PipelineEvent(Instant.now(), Phase.ACT, null, message);
    }

    /** Convenience factory for a {@link Phase#BLOCKED} event. */
    public static PipelineEvent blocked(PipelineBlockerKind kind, String message) {
        return new PipelineEvent(Instant.now(), Phase.BLOCKED, kind, message);
    }
}
