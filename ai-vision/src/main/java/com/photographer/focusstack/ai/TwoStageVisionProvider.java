package com.photographer.focusstack.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * A {@link VisionProvider} that applies a two-stage approach to minimize API costs.
 *
 * <ul>
 *   <li>Stage 1: cheap/fast pre-filter — answers whether a subject is present.</li>
 *   <li>Stage 2: expensive/accurate classifier — runs only for a positive pre-filter.</li>
 * </ul>
 *
 * <p>A failed pre-filter never invokes the second provider and keeps its typed
 * failure category. A successful negative {@code none} remains distinct from an
 * unsuccessful {@code unclassified} result.</p>
 */
public class TwoStageVisionProvider implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(TwoStageVisionProvider.class);
    private static final double PREFILTER_CONFIDENCE_THRESHOLD = 0.3;

    private final VisionProvider preFilter;
    private final VisionProvider classifier;

    public TwoStageVisionProvider(
            VisionProvider preFilter,
            VisionProvider classifier) {
        this.preFilter = Objects.requireNonNull(preFilter, "preFilter");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    @Override
    public String name() {
        return "two-stage[" + preFilter.name() + "+" + classifier.name() + "]";
    }

    @Override
    public VisionResult classifyImage(byte[] imageData, VisionContext context) {
        VisionResult preCheck = preFilter.classifyImage(imageData, context);
        if (shouldSkip(preCheck)) {
            log.debug("Two-stage pre-filter skipped classification (successful={}, label={})",
                    preCheck.successful(), preCheck.primaryLabel());
            return preCheck;
        }
        return classifier.classifyImage(imageData, context);
    }

    @Override
    public VisionResult describeImage(byte[] imageData, VisionContext context) {
        VisionResult preCheck = preFilter.classifyImage(imageData, context);
        if (shouldSkip(preCheck)) {
            log.debug("Two-stage pre-filter skipped description (successful={}, label={})",
                    preCheck.successful(), preCheck.primaryLabel());
            return shaped(preCheck, "description",
                    preCheck.successful()
                            ? "No subject detected – description skipped"
                            : null,
                    List.of());
        }
        return classifier.describeImage(imageData, context);
    }

    @Override
    public VisionResult detectObjects(byte[] imageData, VisionContext context) {
        VisionResult preCheck = preFilter.classifyImage(imageData, context);
        if (shouldSkip(preCheck)) {
            log.debug("Two-stage pre-filter skipped detection (successful={}, label={})",
                    preCheck.successful(), preCheck.primaryLabel());
            return shaped(preCheck, "object_detection",
                    preCheck.successful()
                            ? "No subject detected – object detection skipped"
                            : null,
                    List.of());
        }
        return classifier.detectObjects(imageData, context);
    }

    @Override
    public VisionResult rankBestImage(
            List<byte[]> images,
            VisionContext context) {
        return classifier.rankBestImage(images, context);
    }

    @Override
    public boolean isAvailable() {
        return preFilter.isAvailable() && classifier.isAvailable();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return classifier.capabilities();
    }

    public VisionProvider getPreFilter() {
        return preFilter;
    }

    public VisionProvider getClassifier() {
        return classifier;
    }

    private static VisionResult shaped(
            VisionResult source,
            String taskType,
            String summary,
            List<com.photographer.focusstack.observer.events.BoundingBox> boxes) {
        return new VisionResult(
                source.provider(),
                source.model(),
                taskType,
                source.primaryLabel(),
                source.alternativeLabels(),
                source.confidence(),
                summary,
                boxes,
                source.rawResponse(),
                source.durationMs(),
                source.successful(),
                source.failureKind(),
                source.retryable(),
                source.retryAfterSeconds(),
                source.errorMessage());
    }

    private static boolean shouldSkip(VisionResult preCheck) {
        return !preCheck.successful()
                || "none".equals(preCheck.primaryLabel())
                || preCheck.confidence() < PREFILTER_CONFIDENCE_THRESHOLD;
    }
}
