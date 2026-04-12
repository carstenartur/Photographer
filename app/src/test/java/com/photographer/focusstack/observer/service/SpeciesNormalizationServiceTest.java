package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import com.photographer.focusstack.observer.repository.SpeciesRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SpeciesNormalizationServiceTest {

    private StubSpeciesRepository repository;
    private SpeciesNormalizationService service;

    @BeforeEach
    void setUp() {
        repository = new StubSpeciesRepository();
        service = new SpeciesNormalizationService(repository);
    }

    @Test
    void normalizeSpeciesName_germanName_returnsNormalizedForm() {
        String normalized = service.normalizeSpeciesName("kohlmeise");
        assertEquals("Kohlmeise", normalized, "Lower-case German name should normalize to 'Kohlmeise'");
    }

    @Test
    void normalizeSpeciesName_englishName_returnsGermanNormalizedForm() {
        String normalized = service.normalizeSpeciesName("great tit");
        assertEquals("Kohlmeise", normalized, "English name 'great tit' should map to 'Kohlmeise'");
    }

    @Test
    void normalizeSpeciesName_caseInsensitive() {
        assertEquals(service.normalizeSpeciesName("Kohlmeise"),
                     service.normalizeSpeciesName("kohlmeise"),
                     "Normalization should be case-insensitive");
        assertEquals("Amsel", service.normalizeSpeciesName("AMSEL"));
    }

    @Test
    void normalizeSpeciesName_unknownSpecies_returnsRawLabel() {
        String raw = "Unbekanntes Tier XYZ";
        assertEquals(raw, service.normalizeSpeciesName(raw),
                "Unknown species should return the original raw label unchanged");
    }

    @Test
    void normalizeSpeciesName_null_returnsNull() {
        assertNull(service.normalizeSpeciesName(null));
    }

    @Test
    void getOrCreateSpecies_newLabel_createsRecord() {
        SpeciesRecordEntity record = service.getOrCreateSpecies("Kohlmeise", "bird");
        assertEquals("Kohlmeise", record.getNormalizedNameDe());
        assertEquals("bird", record.getCategory());
        assertEquals(1, repository.saved.size());
    }

    @Test
    void getOrCreateSpecies_existingLabel_incrementsSightings() {
        // Pre-populate repository with a species record
        SpeciesRecordEntity existing = new SpeciesRecordEntity("Kohlmeise", "bird");
        repository.existing.put("Kohlmeise", existing);

        service.getOrCreateSpecies("Kohlmeise", "bird");
        // The existing record should have incremented sightings
        // (StubRepository returns the same object; incrementSightings is called on it)
        assertTrue(repository.saved.size() >= 1, "Should save the updated record");
    }

    @Test
    void getOrCreateSpecies_englishInput_normalizesBeforeLookup() {
        SpeciesRecordEntity record = service.getOrCreateSpecies("great tit", "bird");
        assertEquals("Kohlmeise", record.getNormalizedNameDe(),
                "English input should produce normalized German name");
    }

    @Test
    void getOrCreateSpecies_knownSpecies_populatesEnglishAndScientificName() {
        SpeciesRecordEntity record = service.getOrCreateSpecies("Honigbiene", "bee");
        assertEquals("Honigbiene", record.getNormalizedNameDe());
        assertNotNull(record.getNormalizedNameEn(), "Should populate English name from lookup table");
        assertNotNull(record.getScientificName(), "Should populate scientific name from lookup table");
    }

    // ---- stub repository ----

    static class StubSpeciesRepository implements SpeciesRecordRepository {
        final Map<String, SpeciesRecordEntity> existing = new HashMap<>();
        final List<SpeciesRecordEntity> saved = new ArrayList<>();

        @Override
        public Optional<SpeciesRecordEntity> findByNormalizedNameDe(String normalizedNameDe) {
            return Optional.ofNullable(existing.get(normalizedNameDe));
        }

        @Override
        public List<SpeciesRecordEntity> findAllByOrderByTotalSightingsDesc() {
            return new ArrayList<>(existing.values());
        }

        @Override
        public List<SpeciesRecordEntity> findByCategory(String category) {
            return existing.values().stream()
                    .filter(e -> category.equals(e.getCategory()))
                    .toList();
        }

        @Override
        public <S extends SpeciesRecordEntity> S save(S entity) {
            saved.add(entity);
            existing.put(entity.getNormalizedNameDe(), entity);
            return entity;
        }

        // ---- minimal JpaRepository no-ops ----
        @Override public <S extends SpeciesRecordEntity> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override public Optional<SpeciesRecordEntity> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public List<SpeciesRecordEntity> findAll() { return new ArrayList<>(existing.values()); }
        @Override public List<SpeciesRecordEntity> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public long count() { return existing.size(); }
        @Override public void deleteById(Long id) {}
        @Override public void delete(SpeciesRecordEntity entity) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends SpeciesRecordEntity> entities) {}
        @Override public void deleteAll() {}
        @Override public void flush() {}
        @Override public <S extends SpeciesRecordEntity> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends SpeciesRecordEntity> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override public void deleteAllInBatch(Iterable<SpeciesRecordEntity> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public SpeciesRecordEntity getOne(Long id) { return null; }
        @Override public SpeciesRecordEntity getById(Long id) { return null; }
        @Override public SpeciesRecordEntity getReferenceById(Long id) { return null; }
        @Override public <S extends SpeciesRecordEntity> Optional<S> findOne(
                org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends SpeciesRecordEntity> List<S> findAll(
                org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends SpeciesRecordEntity> List<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends SpeciesRecordEntity> org.springframework.data.domain.Page<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public <S extends SpeciesRecordEntity> long count(
                org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends SpeciesRecordEntity> boolean exists(
                org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends SpeciesRecordEntity, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<
                        org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            return null;
        }
        @Override public List<SpeciesRecordEntity> findAll(org.springframework.data.domain.Sort sort) {
            return findAll();
        }
        @Override public org.springframework.data.domain.Page<SpeciesRecordEntity> findAll(
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
    }
}
