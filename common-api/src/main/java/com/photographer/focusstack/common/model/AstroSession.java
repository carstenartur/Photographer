package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** Represents a full astrophotography capture and stacking session. */
public record AstroSession(
        @JsonProperty("id") @NotBlank String id,
        @JsonProperty("status") AstroSessionStatus status,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("startedAt") Instant startedAt,
        @JsonProperty("finishedAt") Instant finishedAt,
        @JsonProperty("captureConfig") AstroCaptureConfig captureConfig,
        @JsonProperty("focusConfig") AstroFocusConfig focusConfig,
        @JsonProperty("outputDirectory") String outputDirectory,
        @JsonProperty("frames") List<AstroFrame> frames,
        @JsonProperty("resultImagePath") String resultImagePath,
        @JsonProperty("stackingSummary") String stackingSummary,
        @JsonProperty("stackingProgressPercent") @Min(0) @Max(100) int stackingProgressPercent,
        @JsonProperty("errorMessage") String errorMessage
) {
    public AstroSession {
        if (stackingProgressPercent < 0 || stackingProgressPercent > 100) {
            throw new IllegalArgumentException("stackingProgressPercent must be in 0..100");
        }
    }

    /** Backward-compatible constructor for records created before progress was exposed. */
    public AstroSession(
            String id,
            AstroSessionStatus status,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            AstroCaptureConfig captureConfig,
            AstroFocusConfig focusConfig,
            String outputDirectory,
            List<AstroFrame> frames,
            String resultImagePath,
            String stackingSummary,
            String errorMessage) {
        this(id, status, createdAt, startedAt, finishedAt, captureConfig,
                focusConfig, outputDirectory, frames, resultImagePath,
                stackingSummary,
                status == AstroSessionStatus.COMPLETED ? 100 : 0,
                errorMessage);
    }
}
