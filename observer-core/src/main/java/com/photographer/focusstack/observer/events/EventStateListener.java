package com.photographer.focusstack.observer.events;

/**
 * Listener notified on every state change in an {@link EventStateMachine}.
 */
public interface EventStateListener {
    void onStateChange(EventState from, EventState to, ObservationEvent event);
}
