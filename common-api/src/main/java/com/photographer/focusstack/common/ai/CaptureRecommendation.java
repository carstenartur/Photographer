package com.photographer.focusstack.common.ai;

/**
 * AI-generated capture suggestion (exposure / focus / framing hints).
 *
 * <p>Numeric fields use {@code -1} for "no recommendation".
 */
public record CaptureRecommendation(
        String summary,
        int recommendedIso,
        String recommendedShutterSpeed,
        String recommendedAperture,
        String recommendedMode,
        double confidence
) {
    public static CaptureRecommendation none() {
        return new CaptureRecommendation("no-recommendation", -1, null, null, null, 0.0);
    }
}
