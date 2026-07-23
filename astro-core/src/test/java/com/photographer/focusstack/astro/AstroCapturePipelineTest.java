package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.AstroStackingConfig;
import com.photographer.focusstack.common.model.AstroStackingMode;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstroCapturePipelineTest {

    @Test
    void processesMultipleFramesEndToEndWithHistoricalMeanDefault() {
        AstroCapturePipeline pipeline = pipeline();

        AstroCapturePipeline.PipelineResult result = pipeline.process(frames());

        assertNotNull(result.stackedImage());
        assertEquals(200, result.stackedImage().getWidth());
        assertEquals(200, result.stackedImage().getHeight());
        assertEquals(4, result.frames().size());
        assertTrue(result.framesUsedInStack() >= 1,
                "At least reference frame should be used");
        assertEquals(AstroStackingMode.MEAN, result.stackingConfig().mode());
        assertTrue(result.summary().contains("stacking=MEAN"));
    }

    @Test
    void explicitMedianConfigurationFlowsThroughResultAndSummary() {
        AstroCapturePipeline.PipelineResult result = pipeline().process(
                frames(),
                AstroStackingConfig.median(),
                AstroStackingService.StackingControl.none());

        assertEquals(AstroStackingMode.MEDIAN, result.stackingConfig().mode());
        assertTrue(result.summary().contains("stacking=MEDIAN"));
        assertNotNull(result.stackedImage());
    }

    @Test
    void throwsForEmptyFrameList() {
        assertThrows(IllegalArgumentException.class,
                () -> pipeline().process(List.of()));
    }

    private static AstroCapturePipeline pipeline() {
        return new AstroCapturePipeline(
                new StarDetectionService(),
                new FrameRegistrationService(),
                new AstroStackingService(),
                new NoOpStarTrailDeblurService());
    }

    private static List<BufferedImage> frames() {
        List<BufferedImage> frames = new ArrayList<>();
        frames.add(SyntheticStarImageGenerator.generate(200, 200, 20, 42L, 0, 0));
        frames.add(SyntheticStarImageGenerator.generate(200, 200, 20, 42L, 2, 1));
        frames.add(SyntheticStarImageGenerator.generate(200, 200, 20, 42L, 4, 2));
        frames.add(SyntheticStarImageGenerator.generate(200, 200, 20, 42L, 1, 3));
        return frames;
    }
}
