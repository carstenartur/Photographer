package com.photographer.focusstack.astro.entity;

import com.photographer.focusstack.common.model.*;
import jakarta.persistence.*;
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
    private int frameCount;
    private long exposureTimeMs;
    private int iso;
    private String aperture;
    private long delayBetweenFramesMs;
    private String imageFormat;
    private boolean saveRaw;
    private boolean saveJpeg;
    private boolean captureDarkFrame;

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

    public static AstroSessionEntity create(AstroCaptureConfig captureConfig, AstroFocusConfig focusConfig) {
        AstroSessionEntity e = new AstroSessionEntity();
        e.id = UUID.randomUUID().toString();
        e.status = AstroSessionStatus.CREATED;
        e.createdAt = Instant.now();
        e.frameCount = captureConfig.frameCount();
        e.exposureTimeMs = captureConfig.exposureTimeMs();
        e.iso = captureConfig.iso();
        e.aperture = captureConfig.aperture();
        e.delayBetweenFramesMs = captureConfig.delayBetweenFramesMs();
        e.imageFormat = captureConfig.imageFormat();
        e.saveRaw = captureConfig.saveRaw();
        e.saveJpeg = captureConfig.saveJpeg();
        e.captureDarkFrame = captureConfig.captureDarkFrame();
        e.focusStrategy = focusConfig.strategy();
        e.scanSteps = focusConfig.scanSteps();
        e.scanStepSize = focusConfig.scanStepSize();
        e.initialFocusPosition = focusConfig.initialFocusPosition();
        e.bestFocusPosition = focusConfig.bestFocusPosition();
        return e;
    }

    public AstroSession toRecord() {
        return new AstroSession(
                id, status, createdAt, startedAt, finishedAt,
                new AstroCaptureConfig(frameCount, exposureTimeMs, iso, aperture,
                        delayBetweenFramesMs, imageFormat, saveRaw, saveJpeg, captureDarkFrame),
                new AstroFocusConfig(focusStrategy, scanSteps, scanStepSize, initialFocusPosition, bestFocusPosition),
                outputDirectory, List.of(), resultImagePath, stackingSummary, errorMessage
        );
    }

    // Getters and setters needed for JPA
    public String getId() { return id; }
    public AstroSessionStatus getStatus() { return status; }
    public void setStatus(AstroSessionStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getOutputDirectory() { return outputDirectory; }
    public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
    public String getResultImagePath() { return resultImagePath; }
    public void setResultImagePath(String resultImagePath) { this.resultImagePath = resultImagePath; }
    public String getStackingSummary() { return stackingSummary; }
    public void setStackingSummary(String stackingSummary) { this.stackingSummary = stackingSummary; }
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
}
