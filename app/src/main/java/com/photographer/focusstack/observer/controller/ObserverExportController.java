package com.photographer.focusstack.observer.controller;

import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.repository.ObservationEventRepository;
import com.photographer.focusstack.observer.repository.SpeciesRecordRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API for exporting observer data as CSV or JSON.
 *
 * <p>Provides endpoints for exporting observation events and species records
 * within a configurable date range.</p>
 */
@RestController
@RequestMapping("/api/observer/export")
public class ObserverExportController {

    private static final String CSV_MIME = "text/csv;charset=UTF-8";

    private final ObservationEventRepository eventRepository;
    private final SpeciesRecordRepository speciesRepository;

    public ObserverExportController(ObservationEventRepository eventRepository,
                                    SpeciesRecordRepository speciesRepository) {
        this.eventRepository = eventRepository;
        this.speciesRepository = speciesRepository;
    }

    /**
     * Exports observation events in the given date range as CSV or JSON.
     *
     * @param format {@code csv} or {@code json} (default {@code json})
     * @param from   ISO date string (inclusive), defaults to 30 days ago
     * @param to     ISO date string (inclusive), defaults to today
     */
    @GetMapping("/events")
    public ResponseEntity<byte[]> exportEvents(
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        Instant fromInstant = from != null
                ? LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.now().minusSeconds(30L * 24 * 3600);
        Instant toInstant = to != null
                ? LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.now().plusSeconds(86400);

        List<ObservationEventEntity> events =
                eventRepository.findByTimestampBetweenOrderByTimestampDesc(fromInstant, toInstant);

        if ("csv".equalsIgnoreCase(format)) {
            return csvResponse(buildEventsCsv(events), "events.csv");
        }
        return jsonResponse(buildEventsJson(events), "events.json");
    }

    /**
     * Exports the species list as CSV or JSON.
     *
     * @param format {@code csv} or {@code json} (default {@code json})
     */
    @GetMapping("/species")
    public ResponseEntity<byte[]> exportSpecies(
            @RequestParam(defaultValue = "json") String format) {

        List<SpeciesRecordEntity> species =
                speciesRepository.findAllByOrderByTotalSightingsDesc();

        if ("csv".equalsIgnoreCase(format)) {
            return csvResponse(buildSpeciesCsv(species), "species.csv");
        }
        return jsonResponse(buildSpeciesJson(species), "species.json");
    }

    // ---- CSV builders ----

    private String buildEventsCsv(List<ObservationEventEntity> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,mode,primaryLabel,confidence,cameraId,triggerReason,imagePath\n");
        for (ObservationEventEntity e : events) {
            sb.append(csvEsc(e.getTimestamp() != null ? e.getTimestamp().toString() : "")).append(',');
            sb.append(csvEsc(e.getMode())).append(',');
            String label = e.getAiResult() != null ? e.getAiResult().getPrimaryLabel() : "";
            sb.append(csvEsc(label)).append(',');
            String conf = e.getAiResult() != null
                    ? String.format("%.4f", e.getAiResult().getConfidence()) : "";
            sb.append(conf).append(',');
            sb.append(csvEsc(e.getCameraId())).append(',');
            sb.append(csvEsc(e.getTriggerReason())).append(',');
            sb.append(csvEsc(e.getBestImagePath())).append('\n');
        }
        return sb.toString();
    }

    private String buildSpeciesCsv(List<SpeciesRecordEntity> species) {
        StringBuilder sb = new StringBuilder();
        sb.append("normalizedNameDe,normalizedNameEn,scientificName,category,totalSightings,firstSeen,lastSeen\n");
        for (SpeciesRecordEntity s : species) {
            sb.append(csvEsc(s.getNormalizedNameDe())).append(',');
            sb.append(csvEsc(s.getNormalizedNameEn())).append(',');
            sb.append(csvEsc(s.getScientificName())).append(',');
            sb.append(csvEsc(s.getCategory())).append(',');
            sb.append(s.getTotalSightings()).append(',');
            sb.append(s.getFirstSeen() != null ? s.getFirstSeen().toString() : "").append(',');
            sb.append(s.getLastSeen() != null ? s.getLastSeen().toString() : "").append('\n');
        }
        return sb.toString();
    }

    // ---- JSON builders ----

    private String buildEventsJson(List<ObservationEventEntity> events) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < events.size(); i++) {
            ObservationEventEntity e = events.get(i);
            String label = e.getAiResult() != null ? e.getAiResult().getPrimaryLabel() : null;
            String conf  = e.getAiResult() != null
                    ? String.format("%.4f", e.getAiResult().getConfidence()) : "null";
            sb.append("  {");
            sb.append("\"id\":").append(jsonStr(e.getId())).append(',');
            sb.append("\"timestamp\":").append(jsonStr(e.getTimestamp() != null ? e.getTimestamp().toString() : null)).append(',');
            sb.append("\"mode\":").append(jsonStr(e.getMode())).append(',');
            sb.append("\"primaryLabel\":").append(jsonStr(label)).append(',');
            sb.append("\"confidence\":").append(conf).append(',');
            sb.append("\"cameraId\":").append(jsonStr(e.getCameraId())).append(',');
            sb.append("\"triggerReason\":").append(jsonStr(e.getTriggerReason())).append(',');
            sb.append("\"imagePath\":").append(jsonStr(e.getBestImagePath()));
            sb.append("}");
            if (i < events.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(']');
        return sb.toString();
    }

    private String buildSpeciesJson(List<SpeciesRecordEntity> species) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < species.size(); i++) {
            SpeciesRecordEntity s = species.get(i);
            sb.append("  {");
            sb.append("\"normalizedNameDe\":").append(jsonStr(s.getNormalizedNameDe())).append(',');
            sb.append("\"normalizedNameEn\":").append(jsonStr(s.getNormalizedNameEn())).append(',');
            sb.append("\"scientificName\":").append(jsonStr(s.getScientificName())).append(',');
            sb.append("\"category\":").append(jsonStr(s.getCategory())).append(',');
            sb.append("\"totalSightings\":").append(s.getTotalSightings()).append(',');
            sb.append("\"firstSeen\":").append(jsonStr(s.getFirstSeen() != null ? s.getFirstSeen().toString() : null)).append(',');
            sb.append("\"lastSeen\":").append(jsonStr(s.getLastSeen() != null ? s.getLastSeen().toString() : null));
            sb.append("}");
            if (i < species.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(']');
        return sb.toString();
    }

    // ---- response helpers ----

    private ResponseEntity<byte[]> csvResponse(String content, String filename) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(CSV_MIME))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }

    private ResponseEntity<byte[]> jsonResponse(String content, String filename) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }

    /** Escapes a value for use in a CSV cell (wraps in quotes if needed). */
    private static String csvEsc(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
