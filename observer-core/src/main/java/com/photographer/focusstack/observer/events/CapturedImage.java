package com.photographer.focusstack.observer.events;

import java.time.Instant;
import java.util.Map;

/**
 * A single captured image within an observation event.
 * Sharpness score (0.0–1.0) is used for best-shot selection.
 */
public record CapturedImage(
        String id,
        String fileName,
        String filePath,
        Instant capturedAt,
        long fileSize,
        double sharpnessScore,
        boolean isBestShot,
        Map<String, String> cameraSettings
) {}
