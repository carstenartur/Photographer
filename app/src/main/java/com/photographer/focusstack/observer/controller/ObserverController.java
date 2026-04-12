package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.observer.config.ObserverProperties;
import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.service.ObserverService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * REST API for the Wildlife Observer.
 *
 * <p>TODO: Add Swagger/OpenAPI annotations for full REST API documentation.</p>
 */
@RestController
@RequestMapping("/api/observer")
public class ObserverController {

    private final ObserverService observerService;
    private final ObserverProperties properties;

    public ObserverController(ObserverService observerService, ObserverProperties properties) {
        this.observerService = observerService;
        this.properties = properties;
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
     * Returns a single observation event by its ID.
     *
     * @param id the UUID of the event
     */
    @GetMapping("/events/{id}")
    public ResponseEntity<ObservationEventEntity> getEvent(@PathVariable String id) {
        return observerService.findEventById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns events filtered by mode (wildlife or macro_pollinator).
     *
     * @param mode   the observer mode to filter by
     * @param limit  maximum number of events to return (default 50)
     */
    @GetMapping("/events/by-mode")
    public ResponseEntity<List<ObservationEventEntity>> getEventsByMode(
            @RequestParam String mode,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(observerService.findEventsByMode(mode, limit));
    }

    /**
     * Returns events for a given date.
     *
     * @param date ISO date string (yyyy-MM-dd), defaults to today
     */
    @GetMapping("/events/by-date")
    public ResponseEntity<List<ObservationEventEntity>> getEventsByDate(
            @RequestParam(required = false) String date) {
        LocalDate localDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        Instant from = localDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = localDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return ResponseEntity.ok(observerService.findEventsByDateRange(from, to));
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

    /**
     * Returns the current observer status including configuration.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = Map.of(
                "enabled", properties.isEnabled(),
                "defaultProfile", properties.getDefaultProfile(),
                "preferredAiProvider", properties.getPreferredAiProvider(),
                "folderModeEnabled", properties.getFolder().isEnabled()
        );
        return ResponseEntity.ok(status);
    }

    /**
     * Returns the list of available observer profiles.
     */
    @GetMapping("/profiles")
    public ResponseEntity<List<Map<String, Object>>> getProfiles() {
        List<ObserverProfile> profiles = List.of(
                ObserverProfile.birdhouse(),
                ObserverProfile.birdbath(),
                ObserverProfile.macroFlower(),
                ObserverProfile.insectHotel(),
                ObserverProfile.gardenWildlife()
        );
        List<Map<String, Object>> result = profiles.stream().map(p -> Map.<String, Object>of(
                "name", p.name(),
                "mode", p.mode(),
                "previewIntervalMs", p.previewIntervalMs(),
                "motionThreshold", p.motionThreshold(),
                "burstCount", p.burstCount(),
                "focusBracketing", p.focusBracketing(),
                "cooldownSeconds", p.cooldownSeconds(),
                "activeTimeStart", p.activeTimeStart(),
                "activeTimeEnd", p.activeTimeEnd(),
                "maxEventsPerHour", p.maxEventsPerHour()
        )).toList();
        return ResponseEntity.ok(result);
    }
}
