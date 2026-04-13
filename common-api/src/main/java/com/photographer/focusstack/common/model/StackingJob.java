package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public record StackingJob(
        @JsonProperty("id") @NotBlank String id,
        @JsonProperty("cameraProfileId") @NotBlank String cameraProfileId,
        @JsonProperty("status") JobStatus status,
        @JsonProperty("startedAt") Instant startedAt,
        @JsonProperty("completedAt") Instant completedAt,
        @JsonProperty("parameters") StackingParameters parameters,
        @JsonProperty("frames") List<String> frames,
        @JsonProperty("stopReason") String stopReason
) {
}