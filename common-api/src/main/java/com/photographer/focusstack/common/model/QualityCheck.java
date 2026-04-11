package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Quality check result for a captured frame. */
public record QualityCheck(
        @JsonProperty("frameId") String frameId,
        @JsonProperty("sharpnessScore") double sharpnessScore,
        @JsonProperty("exposureScore") double exposureScore,
        @JsonProperty("faceDetected") boolean faceDetected,
        @JsonProperty("faceConfidence") double faceConfidence,
        @JsonProperty("passed") boolean passed,
        @JsonProperty("reason") String reason
) {
}
