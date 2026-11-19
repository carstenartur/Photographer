package com.photographer.focusstack.observer.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * JPA entity for AI analysis results linked to an observation event.
 */
@Entity
@Table(name = "ai_results")
public class AiResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "event_id", nullable = false)
    @JsonIgnore
    private ObservationEventEntity event;

    private String provider;
    private String model;
    private String taskType;
    private String primaryLabel;
    private String failureKind;

    @Column(columnDefinition = "TEXT")
    private String alternativeLabelsJson;

    private double confidence;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    @Column(columnDefinition = "TEXT")
    private String boundingBoxesJson;

    private long durationMs;

    public AiResultEntity() {}

    public Long getId() { return id; }

    public ObservationEventEntity getEvent() { return event; }
    public void setEvent(ObservationEventEntity event) { this.event = event; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getPrimaryLabel() { return primaryLabel; }
    public void setPrimaryLabel(String primaryLabel) { this.primaryLabel = primaryLabel; }

    public String getAlternativeLabelsJson() { return alternativeLabelsJson; }
    public void setAlternativeLabelsJson(String json) { this.alternativeLabelsJson = json; }

    public String getFailureKind() { return failureKind; }
    public void setFailureKind(String failureKind) { this.failureKind = failureKind; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public String getBoundingBoxesJson() { return boundingBoxesJson; }
    public void setBoundingBoxesJson(String boundingBoxesJson) { this.boundingBoxesJson = boundingBoxesJson; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
