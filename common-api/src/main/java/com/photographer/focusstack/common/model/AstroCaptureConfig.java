package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** Capture and stacking configuration for an astrophotography session. */
public record AstroCaptureConfig(
        @JsonProperty("cameraProfileId") String cameraProfileId,
        @JsonProperty("frameCount") @Positive int frameCount,
        @JsonProperty("exposureTimeMs") @Positive long exposureTimeMs,
        @JsonProperty("iso") @Positive int iso,
        @JsonProperty("aperture") String aperture,
        @JsonProperty("delayBetweenFramesMs") @PositiveOrZero long delayBetweenFramesMs,
        @JsonProperty("imageFormat") String imageFormat,
        @JsonProperty("saveRaw") boolean saveRaw,
        @JsonProperty("saveJpeg") boolean saveJpeg,
        @JsonProperty("captureDarkFrame") boolean captureDarkFrame,
        @JsonProperty("stackingConfig") AstroStackingConfig stackingConfig
) {
    public AstroCaptureConfig {
        stackingConfig = stackingConfig == null
                ? AstroStackingConfig.defaults()
                : stackingConfig;
    }

    /** Backward-compatible constructor for callers that do not select a stacking mode. */
    public AstroCaptureConfig(
            String cameraProfileId,
            int frameCount,
            long exposureTimeMs,
            int iso,
            String aperture,
            long delayBetweenFramesMs,
            String imageFormat,
            boolean saveRaw,
            boolean saveJpeg,
            boolean captureDarkFrame) {
        this(cameraProfileId, frameCount, exposureTimeMs, iso, aperture,
                delayBetweenFramesMs, imageFormat, saveRaw, saveJpeg,
                captureDarkFrame, AstroStackingConfig.defaults());
    }

    /** Backward-compatible constructor for existing callers using the default mock backend. */
    public AstroCaptureConfig(
            int frameCount,
            long exposureTimeMs,
            int iso,
            String aperture,
            long delayBetweenFramesMs,
            String imageFormat,
            boolean saveRaw,
            boolean saveJpeg,
            boolean captureDarkFrame) {
        this(null, frameCount, exposureTimeMs, iso, aperture,
                delayBetweenFramesMs, imageFormat, saveRaw, saveJpeg,
                captureDarkFrame, AstroStackingConfig.defaults());
    }

    public static AstroCaptureConfig defaults() {
        return new AstroCaptureConfig(
                null, 10, 5000, 1600, null, 1000, "PNG",
                false, true, false, AstroStackingConfig.defaults());
    }
}
