package com.photographer.focusstack.observer.entity;

import com.photographer.focusstack.observer.events.EventState;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for persisting observation events.
 */
@Entity
@Table(name = "observation_events", indexes = {
        @Index(name = "idx_obs_event_mode", columnList = "mode"),
        @Index(name = "idx_obs_event_timestamp", columnList = "timestamp")
})
public class ObservationEventEntity {

    @Id
    private String id;

    private Instant timestamp;
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventState state;

    @Column(nullable = false)
    private String mode;

    private String cameraId;
    private String triggerReason;

    @Column(columnDefinition = "TEXT")
    private String roiJson;

    private String bestImagePath;
    private String bestImageFileName;
    private String previewImagePath;
    private String previewSource;

    @OneToOne(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private AiResultEntity aiResult;

    protected ObservationEventEntity() {}

    public ObservationEventEntity(String id) {
        this.id = id;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public EventState getState() { return state; }
    public void setState(EventState state) { this.state = state; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getCameraId() { return cameraId; }
    public void setCameraId(String cameraId) { this.cameraId = cameraId; }

    public String getTriggerReason() { return triggerReason; }
    public void setTriggerReason(String triggerReason) { this.triggerReason = triggerReason; }

    public String getRoiJson() { return roiJson; }
    public void setRoiJson(String roiJson) { this.roiJson = roiJson; }

    public String getBestImagePath() { return bestImagePath; }
    public void setBestImagePath(String bestImagePath) { this.bestImagePath = bestImagePath; }

    public String getBestImageFileName() { return bestImageFileName; }
    public void setBestImageFileName(String bestImageFileName) { this.bestImageFileName = bestImageFileName; }

    public String getPreviewImagePath() { return previewImagePath; }
    public void setPreviewImagePath(String previewImagePath) { this.previewImagePath = previewImagePath; }

    public String getPreviewSource() { return previewSource; }
    public void setPreviewSource(String previewSource) { this.previewSource = previewSource; }

    public AiResultEntity getAiResult() { return aiResult; }
    public void setAiResult(AiResultEntity aiResult) { this.aiResult = aiResult; }
}
