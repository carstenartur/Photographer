package com.photographer.focusstack.ai;

import com.photographer.focusstack.observer.events.BoundingBox;

import java.util.List;

/** Unified result returned by all {@link VisionProvider} implementations. */
public record VisionResult(
        String provider,
        String model,
        String taskType,
        String primaryLabel,
        List<String> alternativeLabels,
        double confidence,
        String summary,
        List<BoundingBox> boundingBoxes,
        String rawResponse,
        long durationMs,
        boolean successful,
        VisionFailureKind failureKind,
        boolean retryable,
        Integer retryAfterSeconds,
        String errorMessage
) {
    public VisionResult {
        alternativeLabels = alternativeLabels == null
                ? List.of()
                : List.copyOf(alternativeLabels);
        boundingBoxes = boundingBoxes == null
                ? List.of()
                : List.copyOf(boundingBoxes);
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        if (retryAfterSeconds != null && retryAfterSeconds < 0) {
            throw new IllegalArgumentException(
                    "retryAfterSeconds must not be negative");
        }
        if (successful) {
            failureKind = VisionFailureKind.NONE;
            retryable = false;
            retryAfterSeconds = null;
            errorMessage = null;
        } else {
            failureKind = failureKind == null
                    || failureKind == VisionFailureKind.NONE
                    ? VisionFailureKind.UNKNOWN
                    : failureKind;
            if (!retryable) {
                retryAfterSeconds = null;
            }
            primaryLabel = "unclassified";
            confidence = 0.0;
            boundingBoxes = List.of();
            rawResponse = null;
        }
    }

    /**
     * Compatibility constructor for providers compiled against the original result schema.
     * Failed legacy values are classified as {@link VisionFailureKind#UNKNOWN}.
     */
    public VisionResult(
            String provider,
            String model,
            String taskType,
            String primaryLabel,
            List<String> alternativeLabels,
            double confidence,
            String summary,
            List<BoundingBox> boundingBoxes,
            String rawResponse,
            long durationMs,
            boolean successful,
            String errorMessage) {
        this(provider, model, taskType, primaryLabel, alternativeLabels,
                confidence, summary, boundingBoxes, rawResponse, durationMs,
                successful,
                successful ? VisionFailureKind.NONE : VisionFailureKind.UNKNOWN,
                false,
                null,
                errorMessage);
    }

    /** Factory for a compatibility error result with an unknown category. */
    public static VisionResult error(String provider, String model, String error) {
        return failure(provider, model, "error", VisionFailureKind.UNKNOWN,
                false, null, error, 0L);
    }

    /** Factory for a provider-neutral typed failure result. */
    public static VisionResult failure(
            String provider,
            String model,
            String taskType,
            VisionFailureKind failureKind,
            boolean retryable,
            Integer retryAfterSeconds,
            String safeMessage,
            long durationMs) {
        return new VisionResult(
                provider,
                model,
                taskType,
                "unclassified",
                List.of(),
                0.0,
                null,
                List.of(),
                null,
                durationMs,
                false,
                failureKind,
                retryable,
                retryAfterSeconds,
                safeMessage);
    }
}
