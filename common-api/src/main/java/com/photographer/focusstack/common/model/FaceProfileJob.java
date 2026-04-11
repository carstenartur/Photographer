package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** Job status for 3D face profile generation. */
public record FaceProfileJob(
        @JsonProperty("jobId") @NotBlank String jobId,
        @JsonProperty("sessionId") @NotBlank String sessionId,
        @JsonProperty("status") SessionStatus status,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("completedAt") Instant completedAt,
        @JsonProperty("resultPath") String resultPath,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("vertexCount") int vertexCount,
        @JsonProperty("confidence") double confidence
) {
}
