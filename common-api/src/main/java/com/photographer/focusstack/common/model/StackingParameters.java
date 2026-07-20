package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import jakarta.validation.constraints.Positive;

/** Parameters and explicit capture strategy for one stacking job. */
public record StackingParameters(
        @JsonProperty("startPosition") int startPosition,
        @JsonProperty("stepSize") @Positive int stepSize,
        @JsonProperty("maxFrames") @Positive int maxFrames,
        @JsonProperty("abortThreshold") double abortThreshold,
        @JsonProperty("motionDetectionEnabled") boolean motionDetectionEnabled,
        @JsonProperty("qualityMode") QualityMode qualityMode,
        @JsonProperty("captureStrategy") StackingCaptureStrategy captureStrategy,
        @JsonProperty("nativeFocusBracketing") NativeFocusBracketingRequest nativeFocusBracketing
) {

    public StackingParameters {
        captureStrategy = captureStrategy == null
                ? StackingCaptureStrategy.MANUAL
                : captureStrategy;
        if (captureStrategy != StackingCaptureStrategy.MANUAL
                && nativeFocusBracketing == null) {
            nativeFocusBracketing = new NativeFocusBracketingRequest(
                    maxFrames, stepSize, false, false, false);
        }
    }

    /** Backward-compatible constructor preserving the former manual strategy. */
    public StackingParameters(
            int startPosition,
            int stepSize,
            int maxFrames,
            double abortThreshold,
            boolean motionDetectionEnabled,
            QualityMode qualityMode) {
        this(startPosition, stepSize, maxFrames, abortThreshold,
                motionDetectionEnabled, qualityMode,
                StackingCaptureStrategy.MANUAL, null);
    }
}
