package com.photographer.focusstack.observer.events;

import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionResult;

import java.util.List;

/**
 * AI vision analysis result stored within an observation event.
 * Decoupled from any specific vision provider.
 */
public record VisionResultData(
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
        VisionFailureKind failureKind
) {
    /** Legacy providers can keep returning their historical ten-field result. */
    public VisionResultData(String provider, String model, String taskType, String primaryLabel,
            List<String> alternativeLabels, double confidence, String summary,
            List<BoundingBox> boundingBoxes, String rawResponse, long durationMs) {
        this(provider, model, taskType, primaryLabel, alternativeLabels, confidence, summary,
                boundingBoxes, rawResponse, durationMs, VisionFailureKind.NONE);
    }

    public VisionResultData {
        if (failureKind == null || failureKind == VisionFailureKind.NONE) {
            failureKind = primaryLabel == null || primaryLabel.isBlank() || "unclassified".equals(primaryLabel)
                    ? VisionFailureKind.UNKNOWN : VisionFailureKind.NONE;
        }
        if (failureKind != VisionFailureKind.NONE) {
            primaryLabel = "unclassified";
            alternativeLabels = List.of();
            confidence = 0;
            summary = null;
            rawResponse = null;
            boundingBoxes = List.of();
        }
    }

    /** Converts a neutral provider result into its persisted observation representation. */
    public static VisionResultData from(VisionResult result) {
        return new VisionResultData(result.provider(), result.model(), result.taskType(), result.primaryLabel(),
                result.alternativeLabels(), result.confidence(), result.summary(), result.boundingBoxes(),
                result.rawResponse(), result.durationMs(), result.failureKind());
    }

    public static VisionResultData unclassified(VisionFailureKind reason) {
        return new VisionResultData(null, null, "classification", null, List.of(), 0,
                null, List.of(), null, 0, reason);
    }
}
