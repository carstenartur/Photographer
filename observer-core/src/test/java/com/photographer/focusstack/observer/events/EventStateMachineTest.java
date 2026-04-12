package com.photographer.focusstack.observer.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventStateMachineTest {

    @Test
    void initialStateIsIdle() {
        EventStateMachine sm = new EventStateMachine();
        assertEquals(EventState.IDLE, sm.getState());
    }

    @Test
    void happyPathTransitions() {
        EventStateMachine sm = new EventStateMachine();
        sm.motionDetected("motion", RegionOfInterest.fullFrame());
        assertEquals(EventState.MOTION_DETECTED, sm.getState());
        sm.startTracking();
        assertEquals(EventState.TRACKING, sm.getState());
        sm.startCapturing();
        assertEquals(EventState.CAPTURING, sm.getState());
        sm.imagesCaptured(List.of());
        assertEquals(EventState.SELECTING, sm.getState());
        sm.bestImageSelected("img-1");
        assertEquals(EventState.ANALYZING, sm.getState());
        sm.analysisComplete(new VisionResultData("mock", "model", "classification",
                "Kohlmeise", List.of(), 0.9, "summary", List.of(), "{}", 100L));
        assertEquals(EventState.COMPLETED, sm.getState());
    }

    @Test
    void discardFromAnyState() {
        EventStateMachine sm = new EventStateMachine();
        sm.motionDetected("wind", RegionOfInterest.fullFrame());
        sm.discard("Wind detected");
        assertEquals(EventState.DISCARDED, sm.getState());
    }

    @Test
    void illegalTransitionThrows() {
        EventStateMachine sm = new EventStateMachine();
        assertThrows(IllegalStateException.class, sm::startCapturing);
    }

    @Test
    void listenersAreNotifiedOnTransition() {
        List<EventState> transitions = new ArrayList<>();
        EventStateMachine sm = new EventStateMachine(
                List.of((from, to, event) -> transitions.add(to)));
        sm.motionDetected("motion", RegionOfInterest.fullFrame());
        sm.startTracking();
        assertEquals(List.of(EventState.MOTION_DETECTED, EventState.TRACKING), transitions);
    }

    @Test
    void resetCreatesNewEvent() {
        EventStateMachine sm = new EventStateMachine();
        sm.motionDetected("motion", RegionOfInterest.fullFrame());
        String firstId = sm.getCurrentEvent().id();
        sm.discard("test");
        sm.reset("cam-1", "wildlife");
        assertEquals(EventState.IDLE, sm.getState());
        assertNotEquals(firstId, sm.getCurrentEvent().id());
    }

    @Test
    void currentEventReflectsBuiltState() {
        EventStateMachine sm = new EventStateMachine();
        sm.motionDetected("motion", RegionOfInterest.fullFrame());
        ObservationEvent event = sm.getCurrentEvent();
        assertNotNull(event.id());
        assertEquals(EventState.MOTION_DETECTED, event.state());
        assertEquals("motion", event.triggerReason());
    }
}
