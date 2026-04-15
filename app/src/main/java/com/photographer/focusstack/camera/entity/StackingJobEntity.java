package com.photographer.focusstack.camera.entity;

import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Indexed
@Table(name = "stacking_jobs", indexes = {
        @Index(name = "idx_stacking_job_started_at", columnList = "startedAt")
})
public class StackingJobEntity {

    @Id
    @NotBlank
    private String id;

    @Column(nullable = false)
    @NotBlank
    @KeywordField
    private String cameraProfileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @NotNull
    @KeywordField
    private JobStatus status;

    private Instant startedAt;
    private Instant completedAt;

    @Min(0)
    private int paramStartPosition;

    @Positive
    private int paramStepSize;

    @Positive
    @Max(1000)
    private int paramMaxFrames;

    @Min(0)
    @Max(1)
    private double paramAbortThreshold;

    private boolean paramMotionDetectionEnabled;

    @Enumerated(EnumType.STRING)
    @KeywordField
    private QualityMode paramQualityMode;

    private String stopReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "stacking_job_frames", joinColumns = @JoinColumn(name = "job_id"))
    @OrderColumn(name = "frame_order")
    @Column(name = "frame_id", nullable = false)
    private List<String> capturedFrameIds = new ArrayList<>();

    public StackingJobEntity() {
    }

    public static StackingJobEntity fromRecord(StackingJob job) {
        StackingJobEntity entity = new StackingJobEntity();
        entity.id = job.id();
        entity.cameraProfileId = job.cameraProfileId();
        entity.status = job.status();
        entity.startedAt = job.startedAt();
        entity.completedAt = job.completedAt();
        entity.stopReason = job.stopReason();
        if (job.parameters() != null) {
            entity.paramStartPosition = job.parameters().startPosition();
            entity.paramStepSize = job.parameters().stepSize();
            entity.paramMaxFrames = job.parameters().maxFrames();
            entity.paramAbortThreshold = job.parameters().abortThreshold();
            entity.paramMotionDetectionEnabled = job.parameters().motionDetectionEnabled();
            entity.paramQualityMode = job.parameters().qualityMode();
        }
        if (job.frames() != null) {
            entity.capturedFrameIds = new ArrayList<>(job.frames());
        }
        return entity;
    }

    public StackingJob toRecord() {
        StackingParameters params = new StackingParameters(
                paramStartPosition, paramStepSize, paramMaxFrames,
                paramAbortThreshold, paramMotionDetectionEnabled, paramQualityMode
        );
        return new StackingJob(id, cameraProfileId, status, startedAt, completedAt, params,
                new ArrayList<>(capturedFrameIds), stopReason);
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

    public List<String> getCapturedFrameIds() {
        return capturedFrameIds;
    }

    public void setCapturedFrameIds(List<String> capturedFrameIds) {
        this.capturedFrameIds = capturedFrameIds != null ? capturedFrameIds : new ArrayList<>();
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }
}
