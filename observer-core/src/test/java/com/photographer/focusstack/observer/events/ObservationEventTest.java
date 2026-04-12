package com.photographer.focusstack.observer.events;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObservationEventTest {

    @Test
    void builderCreatesEvent() {
        ObservationEvent event = new ObservationEvent.Builder()
                .id("test-id")
                .mode("wildlife")
                .cameraId("cam-1")
                .state(EventState.IDLE)
                .roi(RegionOfInterest.fullFrame())
                .images(List.of())
                .metadata(Map.of("key", "value"))
                .build();

        assertEquals("test-id", event.id());
        assertEquals("wildlife", event.mode());
        assertEquals("cam-1", event.cameraId());
        assertEquals(EventState.IDLE, event.state());
        assertNotNull(event.roi());
        assertTrue(event.images().isEmpty());
        assertEquals("value", event.metadata().get("key"));
    }

    @Test
    void toBuilderPreservesFields() {
        ObservationEvent original = new ObservationEvent.Builder()
                .id("orig-id")
                .mode("macro_pollinator")
                .state(EventState.COMPLETED)
                .build();

        ObservationEvent copy = original.toBuilder().mode("wildlife").build();
        assertEquals("orig-id", copy.id());
        assertEquals("wildlife", copy.mode());
        assertEquals(EventState.COMPLETED, copy.state());
    }

    @Test
    void imagesAreImmutable() {
        ObservationEvent event = new ObservationEvent.Builder().images(List.of()).build();
        assertThrows(UnsupportedOperationException.class,
                () -> event.images().add(null));
    }

    @Test
    void nullImagesDefaultsToEmpty() {
        ObservationEvent event = new ObservationEvent.Builder().images(null).build();
        assertNotNull(event.images());
        assertTrue(event.images().isEmpty());
    }
}
