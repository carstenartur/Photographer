package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CameraProfile(
        @JsonProperty("id") @NotBlank String id,
        @JsonProperty("name") @NotBlank String name,
        @JsonProperty("backendType") @NotBlank String backendType,
        @JsonProperty("host") @NotBlank String host,
        @JsonProperty("defaultStepSize") @Positive int defaultStepSize,
        @JsonProperty("maxFrames") @Positive int maxFrames,
        @JsonProperty("movementThreshold") double movementThreshold
) {
}
