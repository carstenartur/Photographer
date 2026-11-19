package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.events.*;
import com.photographer.focusstack.observer.repository.ObservationEventRepository;
import com.photographer.focusstack.observer.repository.SpeciesRecordRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObserverEventPersistenceTest {
    @Test
    void savesOriginalPreviewRegionAndDetectionsWithoutInventingASpecies() {
        var events = mock(ObservationEventRepository.class);
        var species = mock(SpeciesRecordRepository.class);
        var service = new ObserverService(events, species, new JsonMapper());
        var now = Instant.now();
        service.onEventCompleted(new ObservationEvent.Builder().id("event").timestamp(now).completedAt(now)
                .state(EventState.COMPLETED).mode("wildlife").cameraId("camera").triggerReason("local-bird")
                .roi(new RegionOfInterest(.5, .25, .25, .5, "perch"))
                .images(List.of(new CapturedImage("original", "event.cr3", "/photos/event.cr3", now,
                        1024, 0, true, Map.of()))).bestImageId("original")
                .metadata(Map.of("previewPath", "/photos/event.preview.jpg", "previewSource", "capture"))
                .aiResult(new VisionResultData("local-bird", "test", "object_detection", "bird", List.of(), .9,
                        "Species not classified", List.of(new BoundingBox(.5, .25, .2, .3, "bird", .9)), null, 12)).build());
        var captor = org.mockito.ArgumentCaptor.forClass(ObservationEventEntity.class);
        verify(events).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("/photos/event.cr3", saved.getBestImagePath());
        assertEquals("event.cr3", saved.getBestImageFileName());
        assertEquals("/photos/event.preview.jpg", saved.getPreviewImagePath());
        assertEquals("capture", saved.getPreviewSource());
        assertEquals(.5, new JsonMapper().readTree(saved.getRoiJson()).get("x").asDouble());
        assertEquals("bird", new JsonMapper().readTree(saved.getAiResult().getBoundingBoxesJson()).get(0).get("label").asString());
        verifyNoInteractions(species);
    }
}
