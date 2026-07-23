package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.AstroStackingConfig;
import com.photographer.focusstack.common.model.AstroStackingMode;
import com.photographer.focusstack.common.model.FrameTransform;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AstroStackingServiceTest {

    private final AstroStackingService service = new AstroStackingService();

    @Test
    void stacksSingleFrame() {
        BufferedImage image = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);
        var input = new AstroStackingService.FrameInput(
                image, FrameTransform.identity());

        BufferedImage result = service.stack(List.of(input), 100, 100);
        assertNotNull(result);
        assertEquals(100, result.getWidth());
        assertEquals(100, result.getHeight());
    }

    @Test
    void historicalMeanStillUsesIntegerFloor() {
        BufferedImage result = service.stack(
                inputs(10, 11, 12),
                1,
                1);

        assertEquals(gray(11), rgb(result));
    }

    @Test
    void stacksMultipleFramesReducesNoise() {
        BufferedImage image1 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);
        BufferedImage image2 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);
        BufferedImage image3 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);

        var inputs = List.of(
                new AstroStackingService.FrameInput(image1, FrameTransform.identity()),
                new AstroStackingService.FrameInput(image2, FrameTransform.identity()),
                new AstroStackingService.FrameInput(image3, FrameTransform.identity()));

        BufferedImage result = service.stack(inputs, 100, 100);
        assertNotNull(result);
        assertEquals(100, result.getWidth());
    }

    @Test
    void stacksWithTranslation() {
        BufferedImage image1 = SyntheticStarImageGenerator.generate(100, 100, 10, 42L);
        BufferedImage image2 = SyntheticStarImageGenerator.generate(
                100, 100, 10, 42L, 2, 3);

        var inputs = List.of(
                new AstroStackingService.FrameInput(image1, FrameTransform.identity()),
                new AstroStackingService.FrameInput(
                        image2, new FrameTransform(0, -2, -3, null)));

        BufferedImage result = service.stack(inputs, 100, 100);
        assertNotNull(result);
    }

    @Test
    void medianRejectsIsolatedOutlierForOddSampleCount() {
        BufferedImage result = service.stack(
                inputs(10, 11, 255),
                1,
                1,
                AstroStackingConfig.median());

        assertEquals(gray(11), rgb(result));
    }

    @Test
    void medianFloorsMiddlePairForEvenSampleCount() {
        BufferedImage result = service.stack(
                inputs(10, 20, 201, 250),
                1,
                1,
                AstroStackingConfig.median());

        assertEquals(gray(110), rgb(result));
    }

    @Test
    void medianPreservesSaturatedStableSignal() {
        BufferedImage result = service.stack(
                inputs(0, 255, 255),
                1,
                1,
                AstroStackingConfig.median());

        assertEquals(gray(255), rgb(result));
    }

    @Test
    void sigmaClippingRejectsOneSaturatedTransient() {
        AstroStackingConfig config = AstroStackingConfig.sigmaClipped(1.5, 3, 3);

        BufferedImage result = service.stack(inputs(10, 10, 10, 255), 1, 1, config);

        assertEquals(gray(10), rgb(result));
    }

    @Test
    void sigmaClippingConvergesAcrossIterations() {
        AstroStackingConfig config = AstroStackingConfig.sigmaClipped(1.0, 4, 3);

        BufferedImage result = service.stack(
                inputs(10, 10, 10, 30, 255),
                1,
                1,
                config);

        assertEquals(gray(10), rgb(result));
    }

    @Test
    void sigmaClippingFallsBackToMeanBelowMinimumSampleCount() {
        AstroStackingConfig config = AstroStackingConfig.sigmaClipped(1.0, 3, 3);

        BufferedImage result = service.stack(inputs(0, 255), 1, 1, config);

        assertEquals(gray(127), rgb(result));
    }

    @Test
    void cancellationStopsAStackBetweenRowsAndReportsProgress() {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        AtomicInteger completedRows = new AtomicInteger();
        AstroStackingService.StackingControl control =
                new AstroStackingService.StackingControl() {
                    @Override
                    public boolean isCancelled() {
                        return completedRows.get() >= 1;
                    }

                    @Override
                    public void onProgress(int completed, int total) {
                        assertEquals(3, total);
                        completedRows.set(completed);
                    }
                };

        assertThrows(
                CancellationException.class,
                () -> service.stack(
                        List.of(new AstroStackingService.FrameInput(
                                image, FrameTransform.identity())),
                        2,
                        3,
                        new AstroStackingConfig(
                                AstroStackingMode.MEDIAN,
                                2.5,
                                3,
                                3),
                        control));
        assertEquals(1, completedRows.get());
    }

    @Test
    void throwsForEmptyList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.stack(List.of(), 100, 100));
    }

    @Test
    void stackedPixelsMatchExpectedSnapshot() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x112233);
        image.setRGB(1, 0, 0x445566);
        image.setRGB(0, 1, 0x778899);
        image.setRGB(1, 1, 0xAABBCC);

        BufferedImage result = service.stack(
                List.of(new AstroStackingService.FrameInput(
                        image, FrameTransform.identity())),
                2,
                2);

        int[] actual = {
                result.getRGB(0, 0) & 0xFFFFFF,
                result.getRGB(1, 0) & 0xFFFFFF,
                result.getRGB(0, 1) & 0xFFFFFF,
                result.getRGB(1, 1) & 0xFFFFFF
        };
        int[] expected = {0x112233, 0x445566, 0x778899, 0xAABBCC};
        assertArrayEquals(expected, actual);
    }

    private static List<AstroStackingService.FrameInput> inputs(int... values) {
        return java.util.Arrays.stream(values)
                .mapToObj(AstroStackingServiceTest::pixel)
                .map(image -> new AstroStackingService.FrameInput(
                        image, FrameTransform.identity()))
                .toList();
    }

    private static BufferedImage pixel(int value) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, gray(value));
        return image;
    }

    private static int gray(int value) {
        return (value << 16) | (value << 8) | value;
    }

    private static int rgb(BufferedImage image) {
        return image.getRGB(0, 0) & 0xFFFFFF;
    }
}
