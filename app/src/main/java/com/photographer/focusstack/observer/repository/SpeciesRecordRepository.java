package com.photographer.focusstack.observer.repository;

import com.photographer.focusstack.observer.entity.SpeciesRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpeciesRecordRepository extends JpaRepository<SpeciesRecordEntity, Long> {

    Optional<SpeciesRecordEntity> findByNormalizedNameDe(String normalizedNameDe);

    java.util.List<SpeciesRecordEntity> findAllByOrderByTotalSightingsDesc();
}
