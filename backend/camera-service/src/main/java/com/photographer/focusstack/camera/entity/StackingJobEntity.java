package com.photographer.focusstack.camera.entity;

import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "stacking_jobs")
public class StackingJobEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String cameraProfileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private Instant startedAt;
    private Instant completedAt;

    private int paramStartPosition;
    private int paramStepSize;
    private int paramMaxFrames;
    private double paramAbortThreshold;
    private boolean paramMotionDetectionEnabled;

    @Enumerated(EnumType.STRING)
    private QualityMode paramQualityMode;

    @Column(length = 10000)
    private String capturedFrameIds;

    public StackingJobEntity() {
    }

    public static StackingJobEntity fromRecord(StackingJob job) {
        StackingJobEntity entity = new StackingJobEntity();
        entity.id = job.id();
        entity.cameraProfileId = job.cameraProfileId();
        entity.status = job.status();
        entity.startedAt = job.startedAt();
        entity.completedAt = job.completedAt();
        if (job.parameters() != null) {
            entity.paramStartPosition = job.parameters().startPosition();
            entity.paramStepSize = job.parameters().stepSize();
            entity.paramMaxFrames = job.parameters().maxFrames();
            entity.paramAbortThreshold = job.parameters().abortThreshold();
            entity.paramMotionDetectionEnabled = job.parameters().motionDetectionEnabled();
            entity.paramQualityMode = job.parameters().qualityMode();
        }
        if (job.frames() != null && !job.frames().isEmpty()) {
            entity.capturedFrameIds = String.join(",", job.frames());
        } else {
            entity.capturedFrameIds = "";
        }
        return entity;
    }

    public StackingJob toRecord() {
        StackingParameters params = new StackingParameters(
                paramStartPosition, paramStepSize, paramMaxFrames,
                paramAbortThreshold, paramMotionDetectionEnabled, paramQualityMode
        );
        List<String> frames = new ArrayList<>();
        if (capturedFrameIds != null && !capturedFrameIds.isEmpty()) {
            frames = new ArrayList<>(Arrays.asList(capturedFrameIds.split(",")));
        }
        return new StackingJob(id, cameraProfileId, status, startedAt, completedAt, params, frames);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCameraProfileId() {
        return cameraProfileId;
    }

    public void setCameraProfileId(String cameraProfileId) {
        this.cameraProfileId = cameraProfileId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getCapturedFrameIds() {
        return capturedFrameIds;
    }

    public void setCapturedFrameIds(String capturedFrameIds) {
        this.capturedFrameIds = capturedFrameIds;
    }
}
