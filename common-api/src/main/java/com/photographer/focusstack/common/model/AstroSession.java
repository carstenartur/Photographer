package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        @JsonProperty("errorMessage") String errorMessage
) {
}
