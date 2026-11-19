package com.photographer.focusstack.observer.integration;

import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.camera.integration.AbstractPostgresIntegrationTest;
import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.events.EventState;
import com.photographer.focusstack.observer.events.ObservationEvent;
import com.photographer.focusstack.observer.events.VisionResultData;
import com.photographer.focusstack.observer.service.ObserverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ObserverServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObserverService observerService;

    @Test
    void localBirdMediaAndRegionRoundTripWithoutCreatingSpecies() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        observerService.onEventCompleted(new ObservationEvent.Builder()
                .id(id).timestamp(now).completedAt(now).state(EventState.COMPLETED).mode("wildlife")
                .cameraId("perch").triggerReason("local-bird")
                .roi(new com.photographer.focusstack.observer.events.RegionOfInterest(.25, .25, .5, .5, "perch"))
                .images(List.of(new com.photographer.focusstack.observer.events.CapturedImage(id,
                        id + ".cr3", "/photos/" + id + ".cr3", now, 1000, 0, true, Map.of())))
                .bestImageId(id)
                .metadata(Map.of("previewPath", "/photos/" + id + ".preview.jpg", "previewSource", "capture"))
                .aiResult(new VisionResultData("local-bird", "yolox-nano-0.1.1rc0", "object_detection",
                        "bird", List.of(), .9, "Species not classified",
                        List.of(new com.photographer.focusstack.observer.events.BoundingBox(.3, .3, .2, .2, "bird", .9)),
                        null, 10)).build());
        var stored = observerService.findEventById(id).orElseThrow();
        assertEquals("/photos/" + id + ".cr3", stored.getBestImagePath());
        assertEquals("/photos/" + id + ".preview.jpg", stored.getPreviewImagePath());
        assertEquals("capture", stored.getPreviewSource());
        assertTrue(stored.getRoiJson().contains("perch"));
        assertTrue(stored.getAiResult().getBoundingBoxesJson().contains("bird"));
        assertTrue(observerService.findSpeciesByName("bird").isEmpty());
    }

    @Test
    void outageIsPersistedWithoutCreatingASpecies() {
        String id = UUID.randomUUID().toString();
        long before = observerService.getClassificationFailures().getOrDefault("TIMEOUT", 0L);
        observerService.onEventCompleted(new ObservationEvent.Builder()
                .id(id).timestamp(Instant.now()).state(EventState.COMPLETED).mode("wildlife")
                .aiResult(VisionResultData.unclassified(VisionFailureKind.TIMEOUT)).build());
        var stored = observerService.findEventById(id).orElseThrow().getAiResult();
        assertEquals("unclassified", stored.getPrimaryLabel());
        assertEquals("TIMEOUT", stored.getFailureKind());
        assertTrue(observerService.findSpeciesByName("unclassified").isEmpty());
        assertEquals(before + 1L, observerService.getClassificationFailures().get("TIMEOUT").longValue());
    }

    @Test
    void persistAndQueryObservationEvent() {
        restTemplate = restTemplate.withBasicAuth("admin", "TestAdminPassword!42");

        // Create an event via the service
        ObservationEvent event = new ObservationEvent.Builder()
                .id(UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .completedAt(Instant.now())
                .state(EventState.COMPLETED)
                .mode("wildlife")
                .cameraId("test-cam")
                .triggerReason("motion")
                .aiResult(new VisionResultData("mock", "mock-model", "classification",
                        "Kohlmeise", List.of("Blaumeise"), 0.92, "A great tit",
                        List.of(), "{}", 100L))
                .metadata(Map.of())
                .build();

        observerService.onEventCompleted(event);

        // Query via REST
        ResponseEntity<List<ObservationEventEntity>> eventsResponse = restTemplate.exchange(
                "/api/observer/events?limit=10",
                HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, eventsResponse.getStatusCode());
        assertNotNull(eventsResponse.getBody());
        assertFalse(eventsResponse.getBody().isEmpty());

        // Query species
        ResponseEntity<List<SpeciesRecordEntity>> speciesResponse = restTemplate.exchange(
                "/api/observer/species",
                HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, speciesResponse.getStatusCode());
        assertNotNull(speciesResponse.getBody());
        assertFalse(speciesResponse.getBody().isEmpty());

        // Query stats
        ResponseEntity<String> statsResponse = restTemplate.getForEntity(
                "/api/observer/stats", String.class);
        assertEquals(HttpStatus.OK, statsResponse.getStatusCode());
        assertNotNull(statsResponse.getBody());
    }

    @Test
    void dailyStatsAndSpeciesDetail() {
        restTemplate = restTemplate.withBasicAuth("admin", "TestAdminPassword!42");

        // Persist a test event
        String eventId = UUID.randomUUID().toString();
        ObservationEvent event = new ObservationEvent.Builder()
                .id(eventId)
                .timestamp(Instant.now())
                .completedAt(Instant.now())
                .state(EventState.COMPLETED)
                .mode("macro_pollinator")
                .cameraId("macro-cam")
                .triggerReason("motion")
                .aiResult(new VisionResultData("mock", "mock-model", "classification",
                        "Honigbiene", List.of(), 0.88, "A honey bee",
                        List.of(), "{}", 80L))
                .metadata(Map.of())
                .build();
        observerService.onEventCompleted(event);

        // Daily stats
        ResponseEntity<String> dailyResponse = restTemplate.getForEntity(
                "/api/observer/stats/daily?days=3", String.class);
        assertEquals(HttpStatus.OK, dailyResponse.getStatusCode());
        assertNotNull(dailyResponse.getBody());
        assertTrue(dailyResponse.getBody().contains("date"));

        // Species detail
        ResponseEntity<String> speciesDetail = restTemplate.getForEntity(
                "/api/observer/stats/species/Honigbiene", String.class);
        assertEquals(HttpStatus.OK, speciesDetail.getStatusCode());
        assertNotNull(speciesDetail.getBody());
        assertTrue(speciesDetail.getBody().contains("Honigbiene"));

        // Profile detail
        ResponseEntity<String> profileDetail = restTemplate.getForEntity(
                "/api/observer/profiles/birdhouse", String.class);
        assertEquals(HttpStatus.OK, profileDetail.getStatusCode());
        assertNotNull(profileDetail.getBody());
        assertTrue(profileDetail.getBody().contains("birdhouse"));

        // AI providers
        ResponseEntity<String> providersResponse = restTemplate.getForEntity(
                "/api/observer/ai/providers", String.class);
        assertEquals(HttpStatus.OK, providersResponse.getStatusCode());
        assertNotNull(providersResponse.getBody());
        assertTrue(providersResponse.getBody().contains("mock"));

        // Delete event
        restTemplate.delete("/api/observer/events/" + eventId);
        ResponseEntity<String> afterDelete = restTemplate.getForEntity(
                "/api/observer/events/" + eventId, String.class);
        assertEquals(HttpStatus.NOT_FOUND, afterDelete.getStatusCode());
    }
}
