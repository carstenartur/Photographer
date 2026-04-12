package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.observer.config.ObserverProperties;
import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.service.ObserverService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API for the Wildlife Observer.
 *
 * <p>Exposes endpoints for event queries, statistics, profile management,
 * AI provider status, and runtime configuration.</p>
 */
@RestController
@RequestMapping("/api/observer")
public class ObserverController {

    private static final Map<String, ObserverProfile> PROFILE_MAP;

    static {
        PROFILE_MAP = new LinkedHashMap<>();
        for (ObserverProfile p : List.of(
                ObserverProfile.birdhouse(),
                ObserverProfile.birdbath(),
                ObserverProfile.macroFlower(),
                ObserverProfile.insectHotel(),
                ObserverProfile.gardenWildlife())) {
            PROFILE_MAP.put(p.name(), p);
        }
    }

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
        List<Map<String, Object>> result = PROFILE_MAP.values().stream().map(this::profileToMap).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Returns a single observer profile by name.
     *
     * @param name the profile name (e.g. "birdhouse", "macro_flower")
     */
    @GetMapping("/profiles/{name}")
    public ResponseEntity<Map<String, Object>> getProfile(@PathVariable String name) {
        ObserverProfile profile = PROFILE_MAP.get(name);
        if (profile == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(profileToMap(profile));
    }

    // ---- Event lifecycle ----

    /**
     * Deletes a single observation event by its ID.
     *
     * @param id the UUID of the event
     */
    @DeleteMapping("/events/{id}")
    public ResponseEntity<Void> deleteEvent(@PathVariable String id) {
        if (observerService.deleteEvent(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // ---- Extended statistics ----

    /**
     * Returns daily event counts for the last N days.
     *
     * @param days number of days (default 7, max 90)
     */
    @GetMapping("/stats/daily")
    public ResponseEntity<List<Map<String, Object>>> getDailyStats(
            @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 90) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(observerService.getDailyStats(days));
    }

    /**
     * Returns detailed statistics for a specific species.
     *
     * @param name the normalized German name of the species
     */
    @GetMapping("/stats/species/{name}")
    public ResponseEntity<Map<String, Object>> getSpeciesDetail(@PathVariable String name) {
        Optional<SpeciesRecordEntity> species = observerService.findSpeciesByName(name);
        if (species.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        SpeciesRecordEntity s = species.get();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("normalizedNameDe", s.getNormalizedNameDe());
        detail.put("normalizedNameEn", s.getNormalizedNameEn());
        detail.put("scientificName", s.getScientificName());
        detail.put("category", s.getCategory());
        detail.put("totalSightings", s.getTotalSightings());
        detail.put("firstSeen", s.getFirstSeen());
        detail.put("lastSeen", s.getLastSeen());
        return ResponseEntity.ok(detail);
    }

    // ---- Runtime configuration ----

    /**
     * Updates observer runtime configuration (profile, AI provider).
     *
     * @param body map with optional keys: "defaultProfile", "preferredAiProvider", "enabled"
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("defaultProfile")) {
            String profile = body.get("defaultProfile").toString();
            if (!PROFILE_MAP.containsKey(profile)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown profile: " + profile));
            }
            properties.setDefaultProfile(profile);
        }
        if (body.containsKey("preferredAiProvider")) {
            properties.setPreferredAiProvider(body.get("preferredAiProvider").toString());
        }
        if (body.containsKey("enabled")) {
            properties.setEnabled(Boolean.parseBoolean(body.get("enabled").toString()));
        }
        return ResponseEntity.ok(Map.of(
                "enabled", properties.isEnabled(),
                "defaultProfile", properties.getDefaultProfile(),
                "preferredAiProvider", properties.getPreferredAiProvider()
        ));
    }

    // ---- AI provider info ----

    /**
     * Returns a list of available AI providers with their availability status.
     */
    @GetMapping("/ai/providers")
    public ResponseEntity<List<Map<String, Object>>> getAiProviders() {
        // Expose the configured AI providers and their status
        List<Map<String, Object>> providers = List.of(
                Map.of("name", "gemini",
                        "configured", properties.getGemini().getApiKey() != null
                                && !properties.getGemini().getApiKey().isBlank(),
                        "model", properties.getGemini().getModel(),
                        "endpoint", properties.getGemini().getEndpoint()),
                Map.of("name", "mock",
                        "configured", true,
                        "model", "built-in",
                        "endpoint", "local")
        );
        return ResponseEntity.ok(providers);
    }

    private Map<String, Object> profileToMap(ObserverProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("mode", p.mode());
        m.put("previewIntervalMs", p.previewIntervalMs());
        m.put("motionThreshold", p.motionThreshold());
        m.put("minObjectSize", p.minObjectSize());
        m.put("burstCount", p.burstCount());
        m.put("focusBracketing", p.focusBracketing());
        m.put("focusBracketingSteps", p.focusBracketingSteps());
        m.put("cooldownSeconds", p.cooldownSeconds());
        m.put("activeTimeStart", p.activeTimeStart());
        m.put("activeTimeEnd", p.activeTimeEnd());
        m.put("maxEventsPerHour", p.maxEventsPerHour());
        m.put("focusMode", p.focusMode());
        m.put("imageQuality", p.imageQuality());
        return m;
    }
}
