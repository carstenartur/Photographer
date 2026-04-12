package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Configuration for a Face Orbit Capture session. */
public record FaceOrbitSettings(
        @JsonProperty("totalSteps") int totalSteps,
        @JsonProperty("angleRange") double angleRange,
        @JsonProperty("autoMode") boolean autoMode,
        @JsonProperty("qualityCheckEnabled") boolean qualityCheckEnabled,
        @JsonProperty("blurThreshold") double blurThreshold,
        @JsonProperty("exposureThreshold") double exposureThreshold,
        @JsonProperty("faceDetectionRequired") boolean faceDetectionRequired
) {
    public static FaceOrbitSettings defaults() {
        return new FaceOrbitSettings(18, 180.0, false, true, 0.5, 0.3, true);
    }
}
