package com.photographer.focusstack.ai;

import com.photographer.focusstack.observer.events.BoundingBox;

import java.util.List;

/**
 * Unified result returned by all {@link VisionProvider} implementations.
 */
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
        String errorMessage
) {

    /** Factory for error results. */
    public static VisionResult error(String provider, String model, String error) {
        return new VisionResult(provider, model, "error", "none", List.of(), 0.0,
                null, List.of(), null, 0L, false, error);
    }
}
