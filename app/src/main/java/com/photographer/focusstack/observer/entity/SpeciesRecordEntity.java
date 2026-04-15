package com.photographer.focusstack.observer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for tracking species/subject sightings across observation events.
 */
@Entity
@Table(name = "species_records", indexes = {
        @Index(name = "idx_species_total_sightings", columnList = "totalSightings")
})
public class SpeciesRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String normalizedNameDe;

    private String normalizedNameEn;
    private String scientificName;
    private String category;

    private Instant firstSeen;
    private Instant lastSeen;
    private long totalSightings;

    protected SpeciesRecordEntity() {}

    public SpeciesRecordEntity(String normalizedNameDe, String category) {
        this.normalizedNameDe = normalizedNameDe;
        this.category = category;
        this.firstSeen = Instant.now();
        this.lastSeen = Instant.now();
        this.totalSightings = 1;
    }

    public Long getId() { return id; }

    public String getNormalizedNameDe() { return normalizedNameDe; }
    public void setNormalizedNameDe(String name) { this.normalizedNameDe = name; }

    public String getNormalizedNameEn() { return normalizedNameEn; }
    public void setNormalizedNameEn(String name) { this.normalizedNameEn = name; }

    public String getScientificName() { return scientificName; }
    public void setScientificName(String name) { this.scientificName = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Instant getFirstSeen() { return firstSeen; }
    public void setFirstSeen(Instant firstSeen) { this.firstSeen = firstSeen; }

    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }

    public long getTotalSightings() { return totalSightings; }
    public void setTotalSightings(long count) { this.totalSightings = count; }
    public void incrementSightings() { this.totalSightings++; }
}
