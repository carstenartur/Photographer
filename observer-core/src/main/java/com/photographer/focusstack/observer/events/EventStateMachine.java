package com.photographer.focusstack.observer.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * State machine that drives an {@link ObservationEvent} through its lifecycle.
 *
 * <p>Thread safety: instances are not thread-safe. Callers must synchronize externally
 * if shared across threads.</p>
 */
public class EventStateMachine {

    private static final Logger log = LoggerFactory.getLogger(EventStateMachine.class);

    private final List<EventStateListener> listeners;
    private ObservationEvent.Builder eventBuilder;
    private EventState currentState = EventState.IDLE;

    public EventStateMachine() {
        this(List.of());
    }

    public EventStateMachine(List<EventStateListener> listeners) {
        this.listeners = new ArrayList<>(listeners);
        this.eventBuilder = new ObservationEvent.Builder()
                .id(UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .state(EventState.IDLE);
    }

    /** Returns the current state. */
    public EventState getState() {
        return currentState;
    }

    /** Returns a snapshot of the current event. */
    public ObservationEvent getCurrentEvent() {
        return eventBuilder.build();
    }

    /** Adds a state-change listener. */
    public void addListener(EventStateListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener. Returns {@code true} if the
     * listener was present and removed. Use this to avoid leaking long-lived
     * references when the same {@code EventStateMachine} instance outlives a
     * transient subscriber.
     */
    public boolean removeListener(EventStateListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Transitions to {@code next}. Throws {@link IllegalStateException} if the
     * transition is not allowed by {@link EventState#canTransitionTo(EventState)}.
     */
    public void transition(EventState next) {
        if (!currentState.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal state transition: %s → %s".formatted(currentState, next));
        }
        EventState previous = currentState;
        currentState = next;
        eventBuilder.state(next);
        if (next == EventState.COMPLETED || next == EventState.DISCARDED) {
            eventBuilder.completedAt(Instant.now());
        }
        ObservationEvent snapshot = eventBuilder.build();
        log.debug("Observer state: {} → {} (event={})", previous, next, snapshot.id());
        for (EventStateListener listener : listeners) {
            listener.onStateChange(previous, next, snapshot);
        }
    }

    // --- Convenience methods for the typical flow ---

    public void motionDetected(String triggerReason, RegionOfInterest roi) {
        eventBuilder.triggerReason(triggerReason).roi(roi);
        transition(EventState.MOTION_DETECTED);
    }

    public void startTracking() {
        transition(EventState.TRACKING);
    }

    public void startCapturing() {
        transition(EventState.CAPTURING);
    }

    public void imagesCaptured(List<CapturedImage> images) {
        eventBuilder.images(images);
        transition(EventState.SELECTING);
    }

    public void bestImageSelected(String imageId) {
        eventBuilder.bestImageId(imageId);
        transition(EventState.ANALYZING);
    }

    public void analysisComplete(VisionResultData result) {
        eventBuilder.aiResult(result);
        transition(EventState.COMPLETED);
    }

    public void discard(String reason) {
        eventBuilder.triggerReason(reason);
        transition(EventState.DISCARDED);
    }

    /** Resets the state machine to IDLE for the next event. */
    public void reset(String cameraId, String mode) {
        currentState = EventState.IDLE;
        eventBuilder = new ObservationEvent.Builder()
                .id(UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .state(EventState.IDLE)
                .cameraId(cameraId)
                .mode(mode);
    }
}
