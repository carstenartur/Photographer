package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.observer.config.ObserverProperties;
import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.service.ObserverService;
import com.photographer.focusstack.observer.service.ReplayService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API for the Wildlife Observer.
 *
 * <p>Exposes endpoints for event queries, statistics, profile management,
 * AI provider status, and runtime configuration.</p>
 */
@RestController
@RequestMapping("/api/observer")
public class ObserverController {

    /**
     * In-memory profile store. Pre-loaded with the 5 built-in profiles.
     * Uses {@link ConcurrentHashMap} to allow safe concurrent mutation from
     * the create/update profile endpoints.
     */
    private static final Map<String, ObserverProfile> PROFILE_MAP = new ConcurrentHashMap<>();

    static {
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
    private final ReplayService replayService;

    public ObserverController(ObserverService observerService, ObserverProperties properties,
                              ReplayService replayService) {
        this.observerService = observerService;
        this.properties = properties;
        this.replayService = replayService;
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

    /**
     * Creates a new observer profile (stored in memory for the lifetime of this instance).
     *
     * @param body profile fields (must include at least "name" and "mode")
     */
    @PostMapping("/profiles")
    public ResponseEntity<Map<String, Object>> createProfile(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (PROFILE_MAP.containsKey(name)) {
            return ResponseEntity.status(409).body(Map.of("error", "Profile already exists: " + name));
        }
        ObserverProfile profile = buildProfileFromMap(body);
        PROFILE_MAP.put(name, profile);
        return ResponseEntity.status(201).body(profileToMap(profile));
    }

    /**
     * Updates an existing observer profile (stored in memory for the lifetime of this instance).
     *
     * <p>The profile name in the request body (if provided) must match the {@code {name}} path
     * parameter; a mismatch returns 400.</p>
     *
     * @param name the profile name
     * @param body updated profile fields
     */
    @PutMapping("/profiles/{name}")
    public ResponseEntity<Map<String, Object>> updateProfile(@PathVariable String name,
                                                              @RequestBody Map<String, Object> body) {
        if (!PROFILE_MAP.containsKey(name)) {
            return ResponseEntity.notFound().build();
        }
        // Reject requests where body.name contradicts the path parameter
        Object bodyName = body.get("name");
        if (bodyName != null && !bodyName.toString().isBlank() && !name.equals(bodyName.toString())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Profile name in body must match path parameter. Expected: " + name));
        }
        // Force the name field to match the path parameter before building the profile
        Map<String, Object> normalizedBody = new LinkedHashMap<>(body);
        normalizedBody.put("name", name);
        ObserverProfile profile = buildProfileFromMap(normalizedBody);
        PROFILE_MAP.put(name, profile);
        return ResponseEntity.ok(profileToMap(profile));
    }

    /**
     * Tests the configured AI provider availability by checking whether it is reachable/configured.
     *
     * <p>For the Gemini provider, verifies that the API key is set.
     * Always returns {@code true} for the built-in mock provider.</p>
     *
     * @param body optional: "mode" (wildlife/macro_pollinator) — for documentation purposes
     * @return provider info including whether the provider is configured and available
     */
    @PostMapping("/ai/test")
    public ResponseEntity<Map<String, Object>> testAiProvider(@RequestBody Map<String, Object> body) {
        String preferredProvider = properties.getPreferredAiProvider();
        String mode = body.containsKey("mode") ? body.get("mode").toString() : "wildlife";

        boolean available;
        String reason;
        if ("gemini".equalsIgnoreCase(preferredProvider)) {
            String apiKey = properties.getGemini().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                // Also check environment variable
                String envKey = System.getenv("GEMINI_API_KEY");
                available = envKey != null && !envKey.isBlank();
                reason = available ? "API key found in environment" : "GEMINI_API_KEY not set";
            } else {
                available = true;
                reason = "API key configured";
            }
        } else {
            // Mock provider is always available
            available = true;
            reason = "Mock provider always available";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", preferredProvider);
        result.put("mode", mode);
        result.put("available", available);
        result.put("reason", reason);
        result.put("successful", available);
        return ResponseEntity.ok(result);
    }

    /**
     * Downloads the best image for a given observation event.
     *
     * <p>Content type is derived from the file extension. Only paths without {@code ..}
     * segments are served to prevent path traversal.</p>
     *
     * @param id event UUID
     * @return image bytes (JPEG/PNG) or 404 if not found
     */
    @GetMapping("/events/{id}/image")
    public ResponseEntity<byte[]> getEventImage(@PathVariable String id) {
        Optional<ObservationEventEntity> found = observerService.findEventById(id);
        if (found.isEmpty()) {
            return ResponseEntity.<byte[]>notFound().build();
        }
        String pathStr = found.get().getBestImagePath();
        if (pathStr == null || pathStr.isBlank()) {
            return ResponseEntity.<byte[]>notFound().build();
        }
        // Security: reject paths containing relative traversal segments to prevent
        // reading arbitrary files. The path comes from the DB but may have been influenced
        // by an untrusted source.
        if (pathStr.contains("..")) {
            return ResponseEntity.<byte[]>badRequest().build();
        }
        try {
            byte[] data = java.nio.file.Files.readAllBytes(Path.of(pathStr));
            MediaType contentType = detectContentType(pathStr);
            return ResponseEntity.ok().contentType(contentType).body(data);
        } catch (java.io.IOException e) {
            return ResponseEntity.<byte[]>notFound().build();
        }
    }

    /** Derives a {@link MediaType} from the file extension. Defaults to JPEG. */
    private static MediaType detectContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }

    /**
     * Starts a replay session that re-runs stored images through the observer pipeline.
     *
     * @param body JSON with "folderPath", "speedMultiplier" (default 1.0, must be positive), "profileName"
     */
    @PostMapping("/replay")
    public ResponseEntity<Map<String, Object>> startReplay(@RequestBody Map<String, Object> body) {
        String folderPath = (String) body.get("folderPath");
        if (folderPath == null || folderPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "folderPath is required"));
        }
        double speed;
        if (body.containsKey("speedMultiplier")) {
            try {
                speed = Double.parseDouble(body.get("speedMultiplier").toString());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "speedMultiplier must be a positive number"));
            }
        } else {
            speed = 1.0;
        }
        if (speed <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "speedMultiplier must be positive, got: " + speed));
        }
        String profile = body.containsKey("profileName")
                ? body.get("profileName").toString() : properties.getDefaultProfile();

        ReplayService.ReplayStatus status = replayService.startReplay(folderPath, speed, profile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", status.sessionId());
        result.put("folderPath", status.folderPath());
        result.put("profileName", status.profileName());
        result.put("speedMultiplier", status.speedMultiplier());
        result.put("state", status.state());
        result.put("framesProcessed", status.framesProcessed());
        result.put("nonEmptyFrames", status.nonEmptyFrames());
        return ResponseEntity.accepted().body(result);
    }

    // ---- helpers ----

    private ObserverProfile buildProfileFromMap(Map<String, Object> body) {
        String name  = str(body, "name", "custom");
        String mode  = str(body, "mode", "wildlife");
        int previewIntervalMs   = intVal(body, "previewIntervalMs", 500);
        double motionThreshold  = dblVal(body, "motionThreshold", 0.15);
        double minObjectSize    = dblVal(body, "minObjectSize", 0.05);
        int burstCount          = intVal(body, "burstCount", 3);
        boolean focusBracketing = boolVal(body, "focusBracketing", false);
        int focusBracketingSteps= intVal(body, "focusBracketingSteps", 0);
        int cooldownSeconds     = intVal(body, "cooldownSeconds", 30);
        String activeTimeStart  = str(body, "activeTimeStart", "06:00");
        String activeTimeEnd    = str(body, "activeTimeEnd", "21:00");
        int maxEventsPerHour    = intVal(body, "maxEventsPerHour", 120);
        String aiPromptTemplate = str(body, "aiPromptTemplate", null);
        String preferredAiProvider = str(body, "preferredAiProvider", "gemini");
        String focusMode        = str(body, "focusMode", "auto");
        String imageQuality     = str(body, "imageQuality", "jpeg");
        return new ObserverProfile(name, mode, previewIntervalMs, motionThreshold, minObjectSize,
                com.photographer.focusstack.observer.events.RegionOfInterest.fullFrame(),
                burstCount, focusBracketing, focusBracketingSteps, cooldownSeconds,
                activeTimeStart, activeTimeEnd, maxEventsPerHour,
                aiPromptTemplate, preferredAiProvider, focusMode, imageQuality);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key); return v != null ? v.toString() : def;
    }
    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key); return v instanceof Number n ? n.intValue() : def;
    }
    private static double dblVal(Map<String, Object> m, String key, double def) {
        Object v = m.get(key); return v instanceof Number n ? n.doubleValue() : def;
    }
    private static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key); return v != null ? Boolean.parseBoolean(v.toString()) : def;
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
