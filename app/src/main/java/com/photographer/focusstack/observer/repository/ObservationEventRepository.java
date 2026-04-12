package com.photographer.focusstack.observer.repository;

import com.photographer.focusstack.observer.entity.ObservationEventEntity;
import com.photographer.focusstack.observer.events.EventState;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface ObservationEventRepository extends JpaRepository<ObservationEventEntity, String> {

    List<ObservationEventEntity> findByModeOrderByTimestampDesc(String mode);

    List<ObservationEventEntity> findByStateOrderByTimestampDesc(EventState state);

    List<ObservationEventEntity> findByTimestampBetweenOrderByTimestampDesc(Instant from, Instant to);

    long countByMode(String mode);

    long countByTimestampBetween(Instant from, Instant to);

    @Query("SELECT e.mode, COUNT(e) FROM ObservationEventEntity e GROUP BY e.mode")
    List<Object[]> countByModeGrouped();
}
