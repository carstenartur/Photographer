package com.photographer.focusstack.common.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SharpnessMap(
        @JsonProperty("width") int width,
        @JsonProperty("height") int height,
        @JsonProperty("blockSize") int blockSize,
        @JsonProperty("scores") double[][] scores,
        @JsonProperty("overallScore") double overallScore
) {
}
