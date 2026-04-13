package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

/** Capture configuration for an astrophotography session. */
public record AstroCaptureConfig(
        @JsonProperty("frameCount") @Positive int frameCount,
        @JsonProperty("exposureTimeMs") @Positive long exposureTimeMs,
        @JsonProperty("iso") @Positive int iso,
        @JsonProperty("aperture") String aperture,
        @JsonProperty("delayBetweenFramesMs") long delayBetweenFramesMs,
        @JsonProperty("imageFormat") String imageFormat,
        @JsonProperty("saveRaw") boolean saveRaw,
        @JsonProperty("saveJpeg") boolean saveJpeg,
        @JsonProperty("captureDarkFrame") boolean captureDarkFrame
) {
    public static AstroCaptureConfig defaults() {
        return new AstroCaptureConfig(10, 5000, 1600, null, 1000, "PNG", false, true, false);
    }
}
