package com.photographer.focusstack.observer.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventStateTest {

    @Test
    void idleCanTransitionToMotionDetected() {
        assertTrue(EventState.IDLE.canTransitionTo(EventState.MOTION_DETECTED));
    }

    @Test
    void idleCannotTransitionToCapturing() {
        assertFalse(EventState.IDLE.canTransitionTo(EventState.CAPTURING));
    }

    @Test
    void motionDetectedCanTransitionToTracking() {
        assertTrue(EventState.MOTION_DETECTED.canTransitionTo(EventState.TRACKING));
    }

    @Test
    void motionDetectedCanTransitionBackToIdle() {
        assertTrue(EventState.MOTION_DETECTED.canTransitionTo(EventState.IDLE));
    }

    @Test
    void anyStateCanTransitionToDiscardedExceptFinalStates() {
        for (EventState state : EventState.values()) {
            if (state != EventState.COMPLETED && state != EventState.DISCARDED) {
                assertTrue(state.canTransitionTo(EventState.DISCARDED),
                        state + " should be able to transition to DISCARDED");
            }
        }
    }

    @Test
    void completedCannotTransitionAnywhere() {
        for (EventState next : EventState.values()) {
            assertFalse(EventState.COMPLETED.canTransitionTo(next),
                    "COMPLETED should not be able to transition to " + next);
        }
    }

    @Test
    void discardedCannotTransitionAnywhere() {
        for (EventState next : EventState.values()) {
            assertFalse(EventState.DISCARDED.canTransitionTo(next),
                    "DISCARDED should not be able to transition to " + next);
        }
    }

    @Test
    void fullHappyPath() {
        EventState s = EventState.IDLE;
        assertTrue(s.canTransitionTo(EventState.MOTION_DETECTED));
        s = EventState.MOTION_DETECTED;
        assertTrue(s.canTransitionTo(EventState.TRACKING));
        s = EventState.TRACKING;
        assertTrue(s.canTransitionTo(EventState.CAPTURING));
        s = EventState.CAPTURING;
        assertTrue(s.canTransitionTo(EventState.SELECTING));
        s = EventState.SELECTING;
        assertTrue(s.canTransitionTo(EventState.ANALYZING));
        s = EventState.ANALYZING;
        assertTrue(s.canTransitionTo(EventState.COMPLETED));
    }
}
