package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the core astro processing pipeline:
 * star detection → reference frame selection → registration → stacking.
 *
 * This is a pure-logic class with no I/O dependencies.
 * The caller provides captured frames as BufferedImages.
 */
public class AstroCapturePipeline {

    private final StarDetectionService starDetectionService;
    private final FrameRegistrationService registrationService;
    private final AstroStackingService stackingService;
    private final StarTrailDeblurService deblurService;

    public AstroCapturePipeline(StarDetectionService starDetectionService,
                                FrameRegistrationService registrationService,
                                AstroStackingService stackingService,
                                StarTrailDeblurService deblurService) {
        this.starDetectionService = starDetectionService;
        this.registrationService = registrationService;
        this.stackingService = stackingService;
        this.deblurService = deblurService;
    }

    /**
     * Result of processing all frames.
     */
    public record PipelineResult(
            List<AstroFrame> frames,
            BufferedImage stackedImage,
            int referenceFrameIndex,
            int framesUsedInStack,
            String summary
    ) {}

    /**
     * Processes a list of captured frame images through the full pipeline.
     *
     * @param images list of captured images (ordered by capture index)
     * @return pipeline result with frame metadata and stacked image
     */
    public PipelineResult process(List<BufferedImage> images) {
        if (images.isEmpty()) {
            throw new IllegalArgumentException("No images to process");
        }

        int width = images.getFirst().getWidth();
        int height = images.getFirst().getHeight();

        // 1. Detect stars in each frame
        List<List<StarPoint>> allStars = new ArrayList<>();
        List<Double> focusScores = new ArrayList<>();
        for (BufferedImage img : images) {
            List<StarPoint> stars = starDetectionService.detectStars(img);
            allStars.add(stars);
            focusScores.add(starDetectionService.computeFocusScore(stars));
        }

        // 2. Choose reference frame (highest focus score)
        int refIndex = 0;
        double bestScore = focusScores.getFirst();
        for (int i = 1; i < focusScores.size(); i++) {
            if (focusScores.get(i) > bestScore) {
                bestScore = focusScores.get(i);
                refIndex = i;
            }
        }
        List<StarPoint> refStars = allStars.get(refIndex);

        // 3. Register each frame to reference
        List<FrameRegistrationService.RegistrationResult> registrations = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            if (i == refIndex) {
                registrations.add(new FrameRegistrationService.RegistrationResult(
                        FrameTransform.identity(), 0.0, refStars.size(), true));
            } else {
                registrations.add(registrationService.register(refStars, allStars.get(i)));
            }
        }

        // 4. Build AstroFrame metadata
        List<AstroFrame> frames = new ArrayList<>();
        List<AstroStackingService.FrameInput> stackInputs = new ArrayList<>();
        int usedInStack = 0;

        for (int i = 0; i < images.size(); i++) {
            var reg = registrations.get(i);
            boolean used = reg.accepted();
            frames.add(new AstroFrame(
                    i,
                    java.time.Instant.now(),
                    null, // filePath set by caller
                    null, // previewPath
                    allStars.get(i),
                    reg.transform(),
                    reg.accepted() ? 1.0 - reg.error() / 20.0 : 0.0,
                    focusScores.get(i),
                    used
            ));
            if (used) {
                stackInputs.add(new AstroStackingService.FrameInput(images.get(i), reg.transform()));
                usedInStack++;
            }
        }

        // 5. Stack accepted frames
        BufferedImage stacked;
        if (stackInputs.isEmpty()) {
            // Fallback: use reference frame if no frames registered
            stacked = images.get(refIndex);
            usedInStack = 1;
        } else {
            stacked = stackingService.stack(stackInputs, width, height);
        }

        // 6. Apply deblur (no-op for now)
        stacked = deblurService.deblur(stacked);

        String summary = String.format(
                "Processed %d frames, reference=#%d, %d used in stack, %d rejected",
                images.size(), refIndex, usedInStack, images.size() - usedInStack);

        return new PipelineResult(frames, stacked, refIndex, usedInStack, summary);
    }
}
