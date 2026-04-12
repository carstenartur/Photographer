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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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

    private final ObjectMapper objectMapper = new ObjectMapper();
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
     * @param from   ISO date string (inclusive, e.g. "2026-01-01"), defaults to 30 days ago
     * @param to     ISO date string (inclusive, e.g. "2026-12-31"), defaults to today
     */
    @GetMapping("/events")
    public ResponseEntity<byte[]> exportEvents(
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws Exception {

        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = from != null
                    ? LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : Instant.now().minusSeconds(30L * 24 * 3600);
            // toInstant is end-of-day (exclusive start of next day) to include the full last day
            toInstant = to != null
                    ? LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : Instant.now().plusSeconds(86400);
        } catch (DateTimeParseException e) {
            byte[] msg = ("Invalid date format. Use ISO format yyyy-MM-dd. " + e.getMessage())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(msg);
        }

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
            @RequestParam(defaultValue = "json") String format) throws Exception {

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

    private String buildEventsJson(List<ObservationEventEntity> events) throws Exception {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ObservationEventEntity e : events) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", e.getId());
            node.put("timestamp", e.getTimestamp() != null ? e.getTimestamp().toString() : null);
            node.put("mode", e.getMode());
            String label = e.getAiResult() != null ? e.getAiResult().getPrimaryLabel() : null;
            if (label != null) node.put("primaryLabel", label); else node.putNull("primaryLabel");
            if (e.getAiResult() != null) {
                node.put("confidence", e.getAiResult().getConfidence());
            } else {
                node.putNull("confidence");
            }
            node.put("cameraId", e.getCameraId());
            node.put("triggerReason", e.getTriggerReason());
            node.put("imagePath", e.getBestImagePath());
            arr.add(node);
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
    }

    private String buildSpeciesJson(List<SpeciesRecordEntity> species) throws Exception {
        ArrayNode arr = objectMapper.createArrayNode();
        for (SpeciesRecordEntity s : species) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("normalizedNameDe", s.getNormalizedNameDe());
            node.put("normalizedNameEn", s.getNormalizedNameEn());
            node.put("scientificName", s.getScientificName());
            node.put("category", s.getCategory());
            node.put("totalSightings", s.getTotalSightings());
            node.put("firstSeen", s.getFirstSeen() != null ? s.getFirstSeen().toString() : null);
            node.put("lastSeen", s.getLastSeen() != null ? s.getLastSeen().toString() : null);
            arr.add(node);
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
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

    /**
     * Escapes a value for use in a CSV cell and neutralizes spreadsheet formula injection.
     * Values starting with {@code =}, {@code +}, {@code -}, or {@code @} are prefixed
     * with a single quote to prevent execution when opened in Excel/Sheets.
     */
    private static String csvEsc(String value) {
        if (value == null) return "";
        // Neutralize formula injection characters
        String sanitized = value;
        if (!sanitized.isEmpty()) {
            char first = sanitized.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                sanitized = "'" + sanitized;
            }
        }
        if (sanitized.contains(",") || sanitized.contains("\"") || sanitized.contains("\n")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }
}
