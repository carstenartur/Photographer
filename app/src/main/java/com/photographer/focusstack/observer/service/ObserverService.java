package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.observer.entity.AiResultEntity;
import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.events.ObservationEvent;
import com.photographer.focusstack.observer.events.VisionResultData;
import com.photographer.focusstack.observer.pipeline.ObservationEventListener;
import com.photographer.focusstack.observer.repository.ObservationEventRepository;
import com.photographer.focusstack.observer.repository.SpeciesRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spring service that persists completed observation events and maintains species records.
 * Implements {@link ObservationEventListener} to receive events from the pipeline.
 */
@Service
public class ObserverService implements ObservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ObserverService.class);

    private final ObservationEventRepository eventRepository;
    private final SpeciesRecordRepository speciesRepository;

    public ObserverService(ObservationEventRepository eventRepository,
                           SpeciesRecordRepository speciesRepository) {
        this.eventRepository = eventRepository;
        this.speciesRepository = speciesRepository;
    }

    @Override
    @Transactional
    public void onEventCompleted(ObservationEvent event) {
        log.info("Persisting completed observation event: {} label={}",
                event.id(),
                event.aiResult() != null ? event.aiResult().primaryLabel() : "N/A");

        ObservationEventEntity entity = toEntity(event);
        eventRepository.save(entity);

        if (event.aiResult() != null && !"none".equals(event.aiResult().primaryLabel())) {
            updateSpeciesRecord(event.aiResult());
        }
    }

    @Override
    public void onEventDiscarded(ObservationEvent event) {
        log.debug("Observation event discarded: {} reason={}", event.id(), event.triggerReason());
        // Discarded events are not persisted by default
    }

    @Transactional(readOnly = true)
    public List<ObservationEventEntity> findRecentEvents(int limit) {
        return eventRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, limit,
                        org.springframework.data.domain.Sort.by("timestamp").descending())
        ).getContent();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ObservationEventEntity> findEventById(String id) {
        return eventRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ObservationEventEntity> findEventsByMode(String mode, int limit) {
        return eventRepository.findByModeOrderByTimestampDesc(mode).stream()
                .limit(limit)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ObservationEventEntity> findEventsByDateRange(java.time.Instant from, java.time.Instant to) {
        return eventRepository.findByTimestampBetweenOrderByTimestampDesc(from, to);
    }

    @Transactional(readOnly = true)
    public List<SpeciesRecordEntity> getSpeciesRanking() {
        return speciesRepository.findAllByOrderByTotalSightingsDesc();
    }

    @Transactional(readOnly = true)
    public long getTotalEventCount() {
        return eventRepository.count();
    }

    @Transactional(readOnly = true)
    public long getTotalSpeciesCount() {
        return speciesRepository.count();
    }

    @Transactional
    public boolean deleteEvent(String id) {
        if (eventRepository.existsById(id)) {
            eventRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Transactional
    public boolean deleteSpecies(Long id) {
        if (speciesRepository.existsById(id)) {
            speciesRepository.deleteById(id);
            return true;
        }
        return false;
    }

    /**
     * Deletes all observation events older than the given retention period.
     *
     * @param olderThan events with a timestamp before this instant are deleted
     * @return the number of events deleted
     */
    @Transactional
    public long deleteEventsOlderThan(java.time.Instant olderThan) {
        return eventRepository.deleteByTimestampBefore(olderThan);
    }

    /**
     * Returns daily event counts for the last {@code days} days.
     * Each entry maps "date" → ISO date string and "count" → number of events.
     */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> getDailyStats(int days) {
        List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            java.time.LocalDate date = today.minusDays(i);
            java.time.Instant from = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            java.time.Instant to = date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            long count = eventRepository.countByTimestampBetween(from, to);
            result.add(java.util.Map.of("date", date.toString(), "count", count));
        }
        return result;
    }

    /**
     * Returns detailed statistics for a given species name.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<SpeciesRecordEntity> findSpeciesByName(String name) {
        return speciesRepository.findByNormalizedNameDe(name);
    }

    /**
     * Returns per-mode event counts.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> getEventCountsByMode() {
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (Object[] row : eventRepository.countByModeGrouped()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    private ObservationEventEntity toEntity(ObservationEvent event) {
        ObservationEventEntity entity = new ObservationEventEntity(event.id());
        entity.setTimestamp(event.timestamp());
        entity.setCompletedAt(event.completedAt());
        entity.setState(event.state());
        entity.setMode(event.mode());
        entity.setCameraId(event.cameraId());
        entity.setTriggerReason(event.triggerReason());

        if (event.aiResult() != null) {
            AiResultEntity aiEntity = toAiEntity(event.aiResult(), entity);
            entity.setAiResult(aiEntity);
        }

        return entity;
    }

    private AiResultEntity toAiEntity(VisionResultData result, ObservationEventEntity event) {
        AiResultEntity entity = new AiResultEntity();
        entity.setEvent(event);
        entity.setProvider(result.provider());
        entity.setModel(result.model());
        entity.setTaskType(result.taskType());
        entity.setPrimaryLabel(result.primaryLabel());
        entity.setAlternativeLabelsJson(
                result.alternativeLabels() != null ? String.join(",", result.alternativeLabels()) : null);
        entity.setConfidence(result.confidence());
        entity.setSummary(result.summary());
        entity.setRawResponse(result.rawResponse());
        entity.setDurationMs(result.durationMs());
        return entity;
    }

    private void updateSpeciesRecord(VisionResultData aiResult) {
        String label = aiResult.primaryLabel();
        speciesRepository.findByNormalizedNameDe(label)
                .ifPresentOrElse(
                        record -> {
                            record.setLastSeen(java.time.Instant.now());
                            record.incrementSightings();
                            speciesRepository.save(record);
                        },
                        () -> {
                            SpeciesRecordEntity record = new SpeciesRecordEntity(label,
                                    aiResult.taskType() != null ? aiResult.taskType() : "unknown");
                            speciesRepository.save(record);
                        }
                );
    }
}
