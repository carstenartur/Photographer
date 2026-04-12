package com.photographer.focusstack.ai;

import java.util.List;

/**
 * Core interface for all AI vision providers.
 *
 * <p>Implementations must be thread-safe.</p>
 */
public interface VisionProvider {

    /** Human-readable provider name (e.g. "gemini", "openai", "mock"). */
    String name();

    /**
     * Classifies the primary subject in an image.
     *
     * @param imageData image bytes (JPEG or PNG)
     * @param context   analysis context and configuration
     * @return classification result; never {@code null}
     */
    VisionResult classifyImage(byte[] imageData, VisionContext context);

    /**
     * Generates a natural-language description of an image.
     *
     * @param imageData image bytes (JPEG or PNG)
     * @param context   analysis context and configuration
     * @return description result; never {@code null}
     */
    VisionResult describeImage(byte[] imageData, VisionContext context);

    /**
     * Detects objects in an image and returns bounding boxes for each detected subject.
     *
     * @param imageData image bytes (JPEG or PNG)
     * @param context   analysis context and configuration
     * @return detection result with bounding boxes; never {@code null}
     */
    VisionResult detectObjects(byte[] imageData, VisionContext context);

    /**
     * Ranks multiple images and returns the index of the best one.
     *
     * @param images  list of image byte arrays (same scene, different shots)
     * @param context analysis context and configuration
     * @return ranking result; {@code primaryLabel} contains the index as a string
     */
    VisionResult rankBestImage(List<byte[]> images, VisionContext context);

    /** Returns {@code true} when this provider is configured and available. */
    boolean isAvailable();

    /** Returns the capabilities of this provider. */
    ProviderCapabilities capabilities();
}
