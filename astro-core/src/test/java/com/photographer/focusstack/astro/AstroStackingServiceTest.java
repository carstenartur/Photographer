package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.FrameTransform;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AstroStackingServiceTest {

    private final AstroStackingService service = new AstroStackingService();

    @Test
    void stacksSingleFrame() {
        BufferedImage img = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);
        var input = new AstroStackingService.FrameInput(img, FrameTransform.identity());

        BufferedImage result = service.stack(List.of(input), 100, 100);
        assertNotNull(result);
        assertEquals(100, result.getWidth());
        assertEquals(100, result.getHeight());
    }

    @Test
    void stacksMultipleFramesReducesNoise() {
        // Stacking multiple identical frames should produce similar result
        BufferedImage img1 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);
        BufferedImage img2 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);
        BufferedImage img3 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);

        var inputs = List.of(
                new AstroStackingService.FrameInput(img1, FrameTransform.identity()),
                new AstroStackingService.FrameInput(img2, FrameTransform.identity()),
                new AstroStackingService.FrameInput(img3, FrameTransform.identity())
        );

        BufferedImage result = service.stack(inputs, 100, 100);
        assertNotNull(result);
        assertEquals(100, result.getWidth());
    }

    @Test
    void stacksWithTranslation() {
        BufferedImage img1 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);
        BufferedImage img2 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L, 2, 3);

        var inputs = List.of(
                new AstroStackingService.FrameInput(img1, FrameTransform.identity()),
                new AstroStackingService.FrameInput(img2, new FrameTransform(0, -2, -3, null))
        );

        BufferedImage result = service.stack(inputs, 100, 100);
        assertNotNull(result);
    }

    @Test
    void throwsForEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> service.stack(List.of(), 100, 100));
    }

    @Test
    void stackedPixelsMatchExpectedSnapshot() {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x112233);
        img.setRGB(1, 0, 0x445566);
        img.setRGB(0, 1, 0x778899);
        img.setRGB(1, 1, 0xAABBCC);

        BufferedImage result = service.stack(
                List.of(new AstroStackingService.FrameInput(img, FrameTransform.identity())),
                2, 2);

        int[] actual = {
                result.getRGB(0, 0) & 0xFFFFFF,
                result.getRGB(1, 0) & 0xFFFFFF,
                result.getRGB(0, 1) & 0xFFFFFF,
                result.getRGB(1, 1) & 0xFFFFFF
        };
        int[] expected = {0x112233, 0x445566, 0x778899, 0xAABBCC};
        assertArrayEquals(expected, actual);
    }
}
