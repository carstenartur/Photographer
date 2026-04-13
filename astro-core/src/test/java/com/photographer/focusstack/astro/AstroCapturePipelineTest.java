package com.photographer.focusstack.astro;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AstroCapturePipelineTest {

    @Test
    void processesMultipleFramesEndToEnd() {
        StarDetectionService starDetection = new StarDetectionService();
        FrameRegistrationService registration = new FrameRegistrationService();
        AstroStackingService stacking = new AstroStackingService();
        NoOpStarTrailDeblurService deblur = new NoOpStarTrailDeblurService();

        AstroCapturePipeline pipeline = new AstroCapturePipeline(
                starDetection, registration, stacking, deblur);

        // Generate slightly shifted frames
        List<BufferedImage> frames = new ArrayList<>();
        frames.add(SyntheticStarImageGenerator.generate(200, 200, 20, 42L, 0, 0));
        frames.add(SyntheticStarImageGenerator.generate(200, 200, 20, 42L, 2, 1));
        frames.add(SyntheticStarImageGenerator.generate(200, 200, 20, 42L, 4, 2));
        frames.add(SyntheticStarImageGenerator.generate(200, 200, 20, 42L, 1, 3));

        AstroCapturePipeline.PipelineResult result = pipeline.process(frames);

        assertNotNull(result.stackedImage());
        assertEquals(200, result.stackedImage().getWidth());
        assertEquals(200, result.stackedImage().getHeight());
        assertEquals(4, result.frames().size());
        assertTrue(result.framesUsedInStack() >= 1, "At least reference frame should be used");
        assertNotNull(result.summary());
    }

    @Test
    void throwsForEmptyFrameList() {
        AstroCapturePipeline pipeline = new AstroCapturePipeline(
                new StarDetectionService(), new FrameRegistrationService(),
                new AstroStackingService(), new NoOpStarTrailDeblurService());

        assertThrows(IllegalArgumentException.class, () -> pipeline.process(List.of()));
    }
}
