package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult;

import java.util.Objects;

/** Persisted strategy selection and effective sequence parameters for a stacking job. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StackingExecutionMetadata(
        StackingCaptureStrategy requestedStrategy,
        StackingCaptureStrategy selectedStrategy,
        int effectiveFrameCount,
        int effectiveFocusIncrement,
        boolean exposureSmoothing,
        boolean depthComposition,
        boolean cropDepthComposition,
        NativeFocusBracketingExecutionResult.CompletionState nativeCompletionState,
        int observedMediaCount,
        int ingestedFrameCount,
        Boolean restorationComplete,
        String detail
) {

    public StackingExecutionMetadata {
        requestedStrategy = Objects.requireNonNull(requestedStrategy, "requestedStrategy");
        selectedStrategy = Objects.requireNonNull(selectedStrategy, "selectedStrategy");
        if (selectedStrategy == StackingCaptureStrategy.AUTO) {
            throw new IllegalArgumentException("selectedStrategy must be MANUAL or NATIVE");
        }
        if (effectiveFrameCount < 1) {
            throw new IllegalArgumentException("effectiveFrameCount must be positive");
        }
        if (effectiveFocusIncrement < 1) {
            throw new IllegalArgumentException("effectiveFocusIncrement must be positive");
        }
        if (observedMediaCount < 0 || ingestedFrameCount < 0
                || ingestedFrameCount > observedMediaCount) {
            throw new IllegalArgumentException("invalid observed/ingested frame counts");
        }
        detail = detail == null || detail.isBlank() ? null : detail.trim();
    }

    public static StackingExecutionMetadata planned(
            StackingCaptureStrategy requested,
            StackingCaptureStrategy selected,
            int frameCount,
            int focusIncrement,
            boolean exposureSmoothing,
            boolean depthComposition,
            boolean cropDepthComposition) {
        return new StackingExecutionMetadata(
                requested, selected, frameCount, focusIncrement,
                exposureSmoothing, depthComposition, cropDepthComposition,
                null, 0, 0, null, null);
    }

    public StackingExecutionMetadata withNativeResult(
            NativeFocusBracketingExecutionResult result) {
        Objects.requireNonNull(result, "result");
        return new StackingExecutionMetadata(
                requestedStrategy,
                selectedStrategy,
                effectiveFrameCount,
                effectiveFocusIncrement,
                exposureSmoothing,
                depthComposition,
                cropDepthComposition,
                result.completionState(),
                result.observedMedia().size(),
                result.ingestedFrameIds().size(),
                result.restorationComplete(),
                result.partialFailureReason());
    }
}
