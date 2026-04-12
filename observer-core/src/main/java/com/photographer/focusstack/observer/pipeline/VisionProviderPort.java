package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.observer.events.CapturedImage;
import com.photographer.focusstack.observer.events.VisionResultData;

/**
 * Port (interface) for AI vision analysis within the observer pipeline.
 *
 * <p>The actual implementation lives in the {@code ai-vision} module or the
 * {@code app} module. This interface keeps {@code observer-core} free of
 * any AI provider dependencies.</p>
 */
public interface VisionProviderPort {

    /**
     * Classifies the subject in the given image.
     *
     * @param image          the captured image to analyse
     * @param mode           observer mode ("wildlife", "macro_pollinator")
     * @param promptTemplate optional custom prompt template; may be {@code null}
     * @return vision result (never {@code null})
     */
    VisionResultData classify(CapturedImage image, String mode, String promptTemplate);

    /** Returns {@code true} when the provider is configured and reachable. */
    boolean isAvailable();

    /** Human-readable provider name (e.g. "gemini", "mock"). */
    String name();
}
