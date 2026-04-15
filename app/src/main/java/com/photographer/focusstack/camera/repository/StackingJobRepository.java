package com.photographer.focusstack.camera.repository;

import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.common.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface StackingJobRepository extends JpaRepository<StackingJobEntity, String> {

    List<StackingJobEntity> findAllByOrderByStartedAtDesc();

    List<StackingJobEntity> findByStatus(JobStatus status);

    List<StackingJobEntity> findByCameraProfileId(String cameraProfileId);

    List<StackingJobEntity> findByStatusInAndCompletedAtBefore(Collection<JobStatus> statuses, Instant cutoff);
}
