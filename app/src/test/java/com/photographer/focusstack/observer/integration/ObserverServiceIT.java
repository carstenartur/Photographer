package com.photographer.focusstack.observer.integration;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ObserverServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObserverService observerService;

    @Test
    void persistAndQueryObservationEvent() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");

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
}
