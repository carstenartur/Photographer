package com.photographer.focusstack.camera.entity;

import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingCaptureStrategy;
import com.photographer.focusstack.common.model.StackingExecutionMetadata;
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

    @Enumerated(EnumType.STRING)
    @KeywordField
    private StackingCaptureStrategy paramCaptureStrategy;

    private Integer paramNativeNumberOfShots;
    private Integer paramNativeFocusIncrement;
    private Boolean paramNativeExposureSmoothing;
    private Boolean paramNativeDepthComposition;
    private Boolean paramNativeCropDepthComposition;

    @Enumerated(EnumType.STRING)
    @KeywordField
    private StackingCaptureStrategy requestedStrategy;

    @Enumerated(EnumType.STRING)
    @KeywordField
    private StackingCaptureStrategy selectedStrategy;

    private Integer effectiveFrameCount;
    private Integer effectiveFocusIncrement;
    private Boolean effectiveExposureSmoothing;
    private Boolean effectiveDepthComposition;
    private Boolean effectiveCropDepthComposition;

    @Enumerated(EnumType.STRING)
    @KeywordField
    private NativeFocusBracketingExecutionResult.CompletionState nativeCompletionState;

    private Integer observedMediaCount;
    private Integer ingestedFrameCount;
    private Boolean restorationComplete;

    @Column(length = 2048)
    private String executionDetail;

    @Column(length = 2048)
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
            StackingParameters parameters = job.parameters();
            entity.paramStartPosition = parameters.startPosition();
            entity.paramStepSize = parameters.stepSize();
            entity.paramMaxFrames = parameters.maxFrames();
            entity.paramAbortThreshold = parameters.abortThreshold();
            entity.paramMotionDetectionEnabled = parameters.motionDetectionEnabled();
            entity.paramQualityMode = parameters.qualityMode();
            entity.paramCaptureStrategy = parameters.captureStrategy();
            NativeFocusBracketingRequest nativeRequest = parameters.nativeFocusBracketing();
            if (nativeRequest != null) {
                entity.paramNativeNumberOfShots = nativeRequest.numberOfShots();
                entity.paramNativeFocusIncrement = nativeRequest.focusIncrement();
                entity.paramNativeExposureSmoothing = nativeRequest.exposureSmoothing();
                entity.paramNativeDepthComposition = nativeRequest.depthComposition();
                entity.paramNativeCropDepthComposition = nativeRequest.cropDepthComposition();
            }
        }
        if (job.frames() != null) {
            entity.capturedFrameIds = new ArrayList<>(job.frames());
        }
        entity.storeExecutionMetadata(job.executionMetadata());
        return entity;
    }

    private void storeExecutionMetadata(StackingExecutionMetadata metadata) {
        if (metadata == null) {
            return;
        }
        requestedStrategy = metadata.requestedStrategy();
        selectedStrategy = metadata.selectedStrategy();
        effectiveFrameCount = metadata.effectiveFrameCount();
        effectiveFocusIncrement = metadata.effectiveFocusIncrement();
        effectiveExposureSmoothing = metadata.exposureSmoothing();
        effectiveDepthComposition = metadata.depthComposition();
        effectiveCropDepthComposition = metadata.cropDepthComposition();
        nativeCompletionState = metadata.nativeCompletionState();
        observedMediaCount = metadata.observedMediaCount();
        ingestedFrameCount = metadata.ingestedFrameCount();
        restorationComplete = metadata.restorationComplete();
        executionDetail = metadata.detail();
    }

    public StackingJob toRecord() {
        StackingCaptureStrategy strategy = paramCaptureStrategy == null
                ? StackingCaptureStrategy.MANUAL : paramCaptureStrategy;
        NativeFocusBracketingRequest nativeRequest = null;
        if (strategy != StackingCaptureStrategy.MANUAL
                && paramNativeNumberOfShots != null
                && paramNativeFocusIncrement != null) {
            nativeRequest = new NativeFocusBracketingRequest(
                    paramNativeNumberOfShots,
                    paramNativeFocusIncrement,
                    Boolean.TRUE.equals(paramNativeExposureSmoothing),
                    Boolean.TRUE.equals(paramNativeDepthComposition),
                    Boolean.TRUE.equals(paramNativeCropDepthComposition));
        }
        StackingParameters params = new StackingParameters(
                paramStartPosition, paramStepSize, paramMaxFrames,
                paramAbortThreshold, paramMotionDetectionEnabled, paramQualityMode,
                strategy, nativeRequest);
        return new StackingJob(
                id, cameraProfileId, status, startedAt, completedAt, params,
                new ArrayList<>(capturedFrameIds), stopReason, executionMetadata());
    }

    private StackingExecutionMetadata executionMetadata() {
        if (requestedStrategy == null || selectedStrategy == null
                || effectiveFrameCount == null || effectiveFocusIncrement == null) {
            return null;
        }
        return new StackingExecutionMetadata(
                requestedStrategy,
                selectedStrategy,
                effectiveFrameCount,
                effectiveFocusIncrement,
                Boolean.TRUE.equals(effectiveExposureSmoothing),
                Boolean.TRUE.equals(effectiveDepthComposition),
                Boolean.TRUE.equals(effectiveCropDepthComposition),
                nativeCompletionState,
                observedMediaCount == null ? 0 : observedMediaCount,
                ingestedFrameCount == null ? 0 : ingestedFrameCount,
                restorationComplete,
                executionDetail);
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
