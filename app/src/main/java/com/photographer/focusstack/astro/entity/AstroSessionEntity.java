package com.photographer.focusstack.astro.entity;

import com.photographer.focusstack.common.model.AstroCaptureConfig;
import com.photographer.focusstack.common.model.AstroFocusConfig;
import com.photographer.focusstack.common.model.AstroFocusStrategy;
import com.photographer.focusstack.common.model.AstroSession;
import com.photographer.focusstack.common.model.AstroSessionStatus;
import com.photographer.focusstack.common.model.AstroStackingConfig;
import com.photographer.focusstack.common.model.AstroStackingMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "astro_sessions")
public class AstroSessionEntity {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AstroSessionStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant startedAt;
    private Instant finishedAt;

    // Capture config stored as individual columns
    private String cameraProfileId;
    private int frameCount;
    private long exposureTimeMs;
    private int iso;
    private String aperture;
    private long delayBetweenFramesMs;
    private String imageFormat;
    private boolean saveRaw;
    private boolean saveJpeg;
    private boolean captureDarkFrame;

    // Stacking config and scientific provenance
    @Enumerated(EnumType.STRING)
    private AstroStackingMode stackingMode;
    private double stackingSigmaThreshold;
    private int stackingMaxIterations;
    private int stackingMinimumSamples;
    private int stackingProgressPercent;

    // Focus config
    @Enumerated(EnumType.STRING)
    private AstroFocusStrategy focusStrategy;
    private int scanSteps;
    private int scanStepSize;
    private Integer initialFocusPosition;
    private Integer bestFocusPosition;

    private String outputDirectory;
    private String resultImagePath;

    @Column(length = 2000)
    private String stackingSummary;

    @Column(length = 2000)
    private String errorMessage;

    protected AstroSessionEntity() {}

    public static AstroSessionEntity create(
            AstroCaptureConfig captureConfig,
            AstroFocusConfig focusConfig) {
        AstroSessionEntity entity = new AstroSessionEntity();
        entity.id = UUID.randomUUID().toString();
        entity.status = AstroSessionStatus.CREATED;
        entity.createdAt = Instant.now();
        entity.cameraProfileId = captureConfig.cameraProfileId();
        entity.frameCount = captureConfig.frameCount();
        entity.exposureTimeMs = captureConfig.exposureTimeMs();
        entity.iso = captureConfig.iso();
        entity.aperture = captureConfig.aperture();
        entity.delayBetweenFramesMs = captureConfig.delayBetweenFramesMs();
        entity.imageFormat = captureConfig.imageFormat();
        entity.saveRaw = captureConfig.saveRaw();
        entity.saveJpeg = captureConfig.saveJpeg();
        entity.captureDarkFrame = captureConfig.captureDarkFrame();
        AstroStackingConfig stacking = captureConfig.stackingConfig();
        entity.stackingMode = stacking.mode();
        entity.stackingSigmaThreshold = stacking.sigmaThreshold();
        entity.stackingMaxIterations = stacking.maxIterations();
        entity.stackingMinimumSamples = stacking.minimumSamples();
        entity.stackingProgressPercent = 0;
        entity.focusStrategy = focusConfig.strategy();
        entity.scanSteps = focusConfig.scanSteps();
        entity.scanStepSize = focusConfig.scanStepSize();
        entity.initialFocusPosition = focusConfig.initialFocusPosition();
        entity.bestFocusPosition = focusConfig.bestFocusPosition();
        return entity;
    }

    public AstroSession toRecord() {
        return toRecord(stackingProgressPercent);
    }

    public AstroSession toRecord(int activeProgressPercent) {
        AstroStackingConfig resolved = resolvedStackingConfig();
        int persistedProgress = boundedProgress(stackingProgressPercent);
        int effectiveProgress = status == AstroSessionStatus.COMPLETED
                ? 100
                : Math.max(persistedProgress, boundedProgress(activeProgressPercent));
        return new AstroSession(
                id,
                status,
                createdAt,
                startedAt,
                finishedAt,
                new AstroCaptureConfig(
                        cameraProfileId,
                        frameCount,
                        exposureTimeMs,
                        iso,
                        aperture,
                        delayBetweenFramesMs,
                        imageFormat,
                        saveRaw,
                        saveJpeg,
                        captureDarkFrame,
                        resolved),
                new AstroFocusConfig(
                        focusStrategy,
                        scanSteps,
                        scanStepSize,
                        initialFocusPosition,
                        bestFocusPosition),
                outputDirectory,
                List.of(),
                resultImagePath,
                stackingSummary,
                effectiveProgress,
                errorMessage);
    }

    public AstroStackingConfig resolvedStackingConfig() {
        return new AstroStackingConfig(
                getStackingMode(),
                getStackingSigmaThreshold(),
                getStackingMaxIterations(),
                getStackingMinimumSamples());
    }

    private static int boundedProgress(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public String getId() { return id; }
    public AstroSessionStatus getStatus() { return status; }
    public void setStatus(AstroSessionStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getCameraProfileId() { return cameraProfileId; }
    public String getOutputDirectory() { return outputDirectory; }
    public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
    public String getResultImagePath() { return resultImagePath; }
    public void setResultImagePath(String resultImagePath) { this.resultImagePath = resultImagePath; }
    public String getStackingSummary() { return stackingSummary; }
    public void setStackingSummary(String stackingSummary) { this.stackingSummary = stackingSummary; }
    public int getStackingProgressPercent() { return boundedProgress(stackingProgressPercent); }
    public void setStackingProgressPercent(int value) { this.stackingProgressPercent = boundedProgress(value); }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getBestFocusPosition() { return bestFocusPosition; }
    public void setBestFocusPosition(Integer bestFocusPosition) { this.bestFocusPosition = bestFocusPosition; }
    public int getFrameCount() { return frameCount; }
    public long getExposureTimeMs() { return exposureTimeMs; }
    public int getIso() { return iso; }
    public long getDelayBetweenFramesMs() { return delayBetweenFramesMs; }
    public AstroFocusStrategy getFocusStrategy() { return focusStrategy; }
    public int getScanSteps() { return scanSteps; }
    public int getScanStepSize() { return scanStepSize; }
    public AstroStackingMode getStackingMode() {
        return stackingMode == null ? AstroStackingMode.MEAN : stackingMode;
    }
    public double getStackingSigmaThreshold() {
        return Double.isFinite(stackingSigmaThreshold)
                && stackingSigmaThreshold >= 0.1
                ? stackingSigmaThreshold
                : AstroStackingConfig.DEFAULT_SIGMA_THRESHOLD;
    }
    public int getStackingMaxIterations() {
        return stackingMaxIterations >= 1
                ? stackingMaxIterations
                : AstroStackingConfig.DEFAULT_MAX_ITERATIONS;
    }
    public int getStackingMinimumSamples() {
        return stackingMinimumSamples >= 3
                ? stackingMinimumSamples
                : AstroStackingConfig.DEFAULT_MINIMUM_SAMPLES;
    }
}
