package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.observer.events.ObservationEvent;

/**
 * Listener notified when an observation event completes or is discarded.
 */
public interface ObservationEventListener {

    /** Called when an event has been fully processed (captured + analysed). */
    void onEventCompleted(ObservationEvent event);

    /** Called when an event was triggered but later discarded (false positive, etc.). */
    void onEventDiscarded(ObservationEvent event);
}
