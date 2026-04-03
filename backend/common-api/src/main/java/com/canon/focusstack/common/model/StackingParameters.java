package com.canon.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

public record StackingParameters(
        @JsonProperty("startPosition") int startPosition,
        @JsonProperty("stepSize") @Positive int stepSize,
        @JsonProperty("maxFrames") @Positive int maxFrames,
        @JsonProperty("abortThreshold") double abortThreshold,
        @JsonProperty("motionDetectionEnabled") boolean motionDetectionEnabled,
        @JsonProperty("qualityMode") QualityMode qualityMode
) {
}
