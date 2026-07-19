package com.photographer.focusstack.common.camera;

import java.util.List;

/**
 * Vendor-neutral outcome of one camera-owned focus-bracketing sequence.
 *
 * @param expectedFrameCount number of frames requested from the camera
 * @param observedMedia unique media references observed after the trigger
 * @param ingestedFrameIds application frame ids successfully persisted from observed media
 * @param completionState terminal execution state
 * @param partialFailureReason stable human-readable reason for a partial or failed outcome
 * @param restorationComplete whether the prepared camera settings were completely restored
 */
public record NativeFocusBracketingExecutionResult(
        int expectedFrameCount,
        List<CameraMediaReference> observedMedia,
        List<String> ingestedFrameIds,
        CompletionState completionState,
        String partialFailureReason,
        boolean restorationComplete
) {

    public NativeFocusBracketingExecutionResult {
        if (expectedFrameCount < 2) {
            throw new IllegalArgumentException("expectedFrameCount must be at least 2");
        }
        observedMedia = observedMedia == null ? List.of() : List.copyOf(observedMedia);
        ingestedFrameIds = ingestedFrameIds == null ? List.of() : List.copyOf(ingestedFrameIds);
        completionState = java.util.Objects.requireNonNull(completionState, "completionState");
        partialFailureReason = partialFailureReason == null || partialFailureReason.isBlank()
                ? null : partialFailureReason.trim();
        if (observedMedia.size() > expectedFrameCount) {
            throw new IllegalArgumentException(
                    "observedMedia must not exceed expectedFrameCount");
        }
        if (ingestedFrameIds.size() > observedMedia.size()) {
            throw new IllegalArgumentException(
                    "ingestedFrameIds must not exceed observedMedia");
        }
        if (completionState == CompletionState.COMPLETE
                && (observedMedia.size() != expectedFrameCount
                || ingestedFrameIds.size() != expectedFrameCount
                || partialFailureReason != null
                || !restorationComplete)) {
            throw new IllegalArgumentException(
                    "complete sequence requires every expected frame and complete restoration");
        }
    }

    public enum CompletionState {
        COMPLETE,
        PARTIAL,
        TIMED_OUT,
        CANCELLED,
        FAILED
    }
}
