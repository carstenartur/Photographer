package com.canon.focusstack.common.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalysisResult(
        @JsonProperty("imageId") String imageId,
        @JsonProperty("sharpnessScore") double sharpnessScore,
        @JsonProperty("sharpRegionPercentage") double sharpRegionPercentage,
        @JsonProperty("motionDetected") boolean motionDetected,
        @JsonProperty("motionScore") double motionScore,
        @JsonProperty("newInformationGain") double newInformationGain,
        @JsonProperty("recommendContinue") boolean recommendContinue
) {
}
