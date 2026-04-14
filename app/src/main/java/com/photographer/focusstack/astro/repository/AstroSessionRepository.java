package com.photographer.focusstack.astro.repository;

import com.photographer.focusstack.astro.entity.AstroSessionEntity;
import com.photographer.focusstack.common.model.AstroSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AstroSessionRepository extends JpaRepository<AstroSessionEntity, String> {
    List<AstroSessionEntity> findAllByOrderByCreatedAtDesc();
    List<AstroSessionEntity> findByStatus(AstroSessionStatus status);
}
