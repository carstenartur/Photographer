package com.photographer.focusstack.camera.repository;

import com.photographer.focusstack.camera.entity.CameraProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CameraProfileRepository extends JpaRepository<CameraProfileEntity, String> {
}
