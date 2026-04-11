package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Represents a capture session for HDR Timelapse or Face Orbit. */
public record CaptureSession(
        @JsonProperty("sessionId") @NotBlank String sessionId,
        @JsonProperty("type") @NotBlank String type,
        @JsonProperty("status") SessionStatus status,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("startedAt") Instant startedAt,
        @JsonProperty("completedAt") Instant completedAt,
        @JsonProperty("frameIds") List<String> frameIds,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("metadata") Map<String, String> metadata
) {
}
