package com.photographer.focusstack.observer.events;

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
        long durationMs
) {}
