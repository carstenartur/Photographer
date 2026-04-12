package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.service.ObserverService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API for the Wildlife Observer.
 */
@RestController
@RequestMapping("/api/observer")
public class ObserverController {

    private final ObserverService observerService;

    public ObserverController(ObserverService observerService) {
        this.observerService = observerService;
    }

    /**
     * Returns a list of the most recent completed observation events.
     *
     * @param limit maximum number of events to return (default 20)
     */
    @GetMapping("/events")
    public ResponseEntity<List<ObservationEventEntity>> getRecentEvents(
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(observerService.findRecentEvents(limit));
    }

    /**
     * Returns species sorted by total sightings count.
     */
    @GetMapping("/species")
    public ResponseEntity<List<SpeciesRecordEntity>> getSpeciesRanking() {
        return ResponseEntity.ok(observerService.getSpeciesRanking());
    }

    /**
     * Returns summary statistics for the observer.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = Map.of(
                "totalEvents", observerService.getTotalEventCount(),
                "totalSpecies", observerService.getTotalSpeciesCount()
        );
        return ResponseEntity.ok(stats);
    }
}
