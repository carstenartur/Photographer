package com.photographer.focusstack.observer.events;

/**
 * State machine for an observation event lifecycle.
 *
 * <pre>
 * IDLE → MOTION_DETECTED → TRACKING → CAPTURING → SELECTING → ANALYZING → COMPLETED
 *                                                                         → DISCARDED
 * </pre>
 *
 * Any state can transition to DISCARDED.
 */
public enum EventState {
    /** No active event. */
    IDLE,
    /** Motion detected, waiting for confirmation. */
    MOTION_DETECTED,
    /** Object is being tracked, confirmation in progress. */
    TRACKING,
    /** Photos are being captured. */
    CAPTURING,
    /** Best shot is being selected from the capture series. */
    SELECTING,
    /** AI analysis is running. */
    ANALYZING,
    /** Event completed with a result. */
    COMPLETED,
    /** Event discarded (false trigger, wind, no animal detected, etc.). */
    DISCARDED;

    /**
     * Returns {@code true} if this state can transition to {@code next}.
     */
    public boolean canTransitionTo(EventState next) {
        if (next == DISCARDED) {
            return this != COMPLETED && this != DISCARDED;
        }
        return switch (this) {
            case IDLE             -> next == MOTION_DETECTED;
            case MOTION_DETECTED  -> next == TRACKING || next == IDLE;
            case TRACKING         -> next == CAPTURING;
            case CAPTURING        -> next == SELECTING;
            case SELECTING        -> next == ANALYZING;
            case ANALYZING        -> next == COMPLETED;
            case COMPLETED, DISCARDED -> false;
        };
    }
}
