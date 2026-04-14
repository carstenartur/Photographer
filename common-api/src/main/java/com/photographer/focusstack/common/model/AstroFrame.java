package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * A single frame captured during an astrophotography session.
 *
 * @param qualityScore overall quality score in the range 0.0 (worst) to 1.0 (best)
 * @param focusScore   focus sharpness score in the range 0.0 (worst) to 1.0 (best)
 */
public record AstroFrame(
        @JsonProperty("frameIndex") int frameIndex,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("filePath") String filePath,
        @JsonProperty("previewPath") String previewPath,
        @JsonProperty("starPoints") List<StarPoint> starPoints,
        @JsonProperty("transformToReference") FrameTransform transformToReference,
        @JsonProperty("qualityScore") double qualityScore,
        @JsonProperty("focusScore") double focusScore,
        @JsonProperty("usedInStack") boolean usedInStack
) {
}
