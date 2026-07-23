package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.AstroFrame;
import com.photographer.focusstack.common.model.AstroStackingConfig;
import com.photographer.focusstack.common.model.AstroStackingMode;
import com.photographer.focusstack.common.model.FrameTransform;
import com.photographer.focusstack.common.model.StarPoint;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates the core astro processing pipeline:
 * star detection → reference frame selection → registration → stacking.
 *
 * <p>This is a pure-logic class with no I/O dependencies. The caller provides
 * captured frames as {@link BufferedImage} values.</p>
 */
public class AstroCapturePipeline {

    private final StarDetectionService starDetectionService;
    private final FrameRegistrationService registrationService;
    private final AstroStackingService stackingService;
    private final StarTrailDeblurService deblurService;
    private final double maxRegistrationError;

    public AstroCapturePipeline(
            StarDetectionService starDetectionService,
            FrameRegistrationService registrationService,
            AstroStackingService stackingService,
            StarTrailDeblurService deblurService) {
        this.starDetectionService = Objects.requireNonNull(
                starDetectionService, "starDetectionService");
        this.registrationService = Objects.requireNonNull(
                registrationService, "registrationService");
        this.stackingService = Objects.requireNonNull(
                stackingService, "stackingService");
        this.deblurService = Objects.requireNonNull(
                deblurService, "deblurService");
        this.maxRegistrationError = registrationService.getMaxRegistrationError();
    }

    /** Result of processing all frames. */
    public record PipelineResult(
            List<AstroFrame> frames,
            BufferedImage stackedImage,
            int referenceFrameIndex,
            int framesUsedInStack,
            AstroStackingConfig stackingConfig,
            String summary
    ) {
        /**
         * Compatibility constructor for callers compiled against the historical
         * Mean-only result shape.
         */
        public PipelineResult(
                List<AstroFrame> frames,
                BufferedImage stackedImage,
                int referenceFrameIndex,
                int framesUsedInStack,
                String summary) {
            this(
                    frames,
                    stackedImage,
                    referenceFrameIndex,
                    framesUsedInStack,
                    AstroStackingConfig.defaults(),
                    summary);
        }
    }

    /** Processes using the historical Mean stacking configuration. */
    public PipelineResult process(List<BufferedImage> images) {
        return process(
                images,
                AstroStackingConfig.defaults(),
                AstroStackingService.StackingControl.none());
    }

    /** Processes with an explicit stacking configuration and cooperative cancellation. */
    public PipelineResult process(
            List<BufferedImage> images,
            AstroStackingConfig stackingConfig,
            AstroStackingService.StackingControl stackingControl) {
        Objects.requireNonNull(images, "images");
        Objects.requireNonNull(stackingConfig, "stackingConfig");
        Objects.requireNonNull(stackingControl, "stackingControl");
        if (images.isEmpty()) {
            throw new IllegalArgumentException("No images to process");
        }

        int width = images.getFirst().getWidth();
        int height = images.getFirst().getHeight();

        List<List<StarPoint>> allStars = new ArrayList<>();
        List<Double> focusScores = new ArrayList<>();
        for (BufferedImage image : images) {
            List<StarPoint> stars = starDetectionService.detectStars(image);
            allStars.add(stars);
            focusScores.add(starDetectionService.computeFocusScore(stars));
        }

        int referenceIndex = 0;
        double bestScore = focusScores.getFirst();
        for (int index = 1; index < focusScores.size(); index++) {
            if (focusScores.get(index) > bestScore) {
                bestScore = focusScores.get(index);
                referenceIndex = index;
            }
        }
        List<StarPoint> referenceStars = allStars.get(referenceIndex);

        List<FrameRegistrationService.RegistrationResult> registrations =
                new ArrayList<>();
        for (int index = 0; index < images.size(); index++) {
            if (index == referenceIndex) {
                registrations.add(new FrameRegistrationService.RegistrationResult(
                        FrameTransform.identity(), 0.0, referenceStars.size(), true));
            } else {
                registrations.add(registrationService.register(
                        referenceStars,
                        allStars.get(index)));
            }
        }

        List<AstroFrame> frames = new ArrayList<>();
        List<AstroStackingService.FrameInput> stackInputs = new ArrayList<>();
        int usedInStack = 0;

        for (int index = 0; index < images.size(); index++) {
            var registration = registrations.get(index);
            boolean used = registration.accepted();
            frames.add(new AstroFrame(
                    index,
                    java.time.Instant.now(),
                    null,
                    null,
                    allStars.get(index),
                    registration.transform(),
                    registration.accepted()
                            ? Math.max(
                                    0.0,
                                    Math.min(
                                            1.0,
                                            1.0 - registration.error()
                                                    / maxRegistrationError))
                            : 0.0,
                    focusScores.get(index),
                    used));
            if (used) {
                stackInputs.add(new AstroStackingService.FrameInput(
                        images.get(index), registration.transform()));
                usedInStack++;
            }
        }

        BufferedImage stacked;
        if (stackInputs.isEmpty()) {
            stacked = images.get(referenceIndex);
            usedInStack = 1;
        } else {
            stacked = stackingService.stack(
                    stackInputs,
                    width,
                    height,
                    stackingConfig,
                    stackingControl);
        }

        stacked = deblurService.deblur(stacked);

        String summary = String.format(
                "Processed %d frames, reference=#%d, %d used in stack, "
                        + "%d rejected, stacking=%s%s",
                images.size(),
                referenceIndex,
                usedInStack,
                images.size() - usedInStack,
                stackingConfig.mode(),
                stackingConfig.mode() == AstroStackingMode.SIGMA_CLIPPED_MEAN
                        ? String.format(
                                "(sigma=%.3f,iterations=%d,minSamples=%d)",
                                stackingConfig.sigmaThreshold(),
                                stackingConfig.maxIterations(),
                                stackingConfig.minimumSamples())
                        : "");
        return new PipelineResult(
                List.copyOf(frames),
                stacked,
                referenceIndex,
                usedInStack,
                stackingConfig,
                summary);
    }
}
