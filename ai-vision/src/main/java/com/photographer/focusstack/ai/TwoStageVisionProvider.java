package com.photographer.focusstack.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A {@link VisionProvider} that applies a two-stage approach to minimize API costs.
 *
 * <ul>
 *   <li>Stage 1: cheap/fast pre-filter (e.g. local Ollama) — answers "is there a subject at all?"</li>
 *   <li>Stage 2: expensive/accurate classifier (e.g. Gemini) — runs only when Stage 1 is positive</li>
 * </ul>
 *
 * <p>If the pre-filter returns {@code "none"} or a confidence below
 * {@link #PREFILTER_CONFIDENCE_THRESHOLD}, the pre-filter result is returned directly
 * and the expensive classifier is never called.</p>
 *
 * <p>Thread safety: as thread-safe as the wrapped providers.</p>
 */
public class TwoStageVisionProvider implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(TwoStageVisionProvider.class);

    /** Minimum confidence returned by the pre-filter to proceed to the classifier. */
    private static final double PREFILTER_CONFIDENCE_THRESHOLD = 0.3;

    private final VisionProvider preFilter;
    private final VisionProvider classifier;

    /**
     * Creates a two-stage provider.
     *
     * @param preFilter  cheap/fast provider used as the first stage
     * @param classifier expensive/accurate provider used as the second stage
     */
    public TwoStageVisionProvider(VisionProvider preFilter, VisionProvider classifier) {
        this.preFilter = preFilter;
        this.classifier = classifier;
    }

    @Override
    public String name() {
        return "two-stage[" + preFilter.name() + "+" + classifier.name() + "]";
    }

    @Override
    public VisionResult classifyImage(byte[] imageData, VisionContext context) {
        VisionResult preCheck = preFilter.classifyImage(imageData, context);
        if (shouldSkip(preCheck)) {
            log.debug("TwoStageVisionProvider: pre-filter returned '{}' (conf={:.2f}), skipping classifier",
                    preCheck.primaryLabel(), preCheck.confidence());
            return preCheck;
        }
        log.debug("TwoStageVisionProvider: pre-filter detected '{}', running classifier",
                preCheck.primaryLabel());
        return classifier.classifyImage(imageData, context);
    }

    @Override
    public VisionResult describeImage(byte[] imageData, VisionContext context) {
        VisionResult preCheck = preFilter.classifyImage(imageData, context);
        if (shouldSkip(preCheck)) {
            return preCheck;
        }
        return classifier.describeImage(imageData, context);
    }

    @Override
    public VisionResult detectObjects(byte[] imageData, VisionContext context) {
        VisionResult preCheck = preFilter.classifyImage(imageData, context);
        if (shouldSkip(preCheck)) {
            return preCheck;
        }
        return classifier.detectObjects(imageData, context);
    }

    @Override
    public VisionResult rankBestImage(List<byte[]> images, VisionContext context) {
        // For ranking, delegate directly to the classifier
        return classifier.rankBestImage(images, context);
    }

    @Override
    public boolean isAvailable() {
        return preFilter.isAvailable() && classifier.isAvailable();
    }

    @Override
    public ProviderCapabilities capabilities() {
        // Expose capabilities of the classifier (more capable stage)
        return classifier.capabilities();
    }

    /** Returns the pre-filter provider (for introspection/testing). */
    public VisionProvider getPreFilter() {
        return preFilter;
    }

    /** Returns the classifier provider (for introspection/testing). */
    public VisionProvider getClassifier() {
        return classifier;
    }

    private boolean shouldSkip(VisionResult preCheck) {
        return "none".equals(preCheck.primaryLabel())
                || preCheck.confidence() < PREFILTER_CONFIDENCE_THRESHOLD
                || !preCheck.successful();
    }
}
