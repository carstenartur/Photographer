package com.photographer.focusstack.analysis.strategy;

import com.photographer.focusstack.analysis.service.ImageJProcessor;
import com.photographer.focusstack.analysis.service.MotionDetector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructuralSimilarityStrategyTest {

    private final StructuralSimilarityStrategy strategy =
            new StructuralSimilarityStrategy(new ImageJProcessor(), new MotionDetector());

    @Test
    void identicalFramesShouldNotDetectMotion() {
        byte[] frame = generateCheckerboard(64, 64, 4);

        MotionDetector.MotionResult result = strategy.detect(frame, frame, 0.15);
        assertFalse(result.motionDetected(), "Identical frames should not detect motion");
        assertEquals(0.0, result.motionScore(), 0.001);
    }

    @Test
    void blurChangeShouldNotDetectMotion() {
        // Same pattern with reduced contrast simulates defocus blur.
        // Edges stay in identical positions but become weaker.
        byte[] sharp = generateCheckerboard(64, 64, 4);
        byte[] blurry = reduceContrast(sharp, 0.5);

        MotionDetector.MotionResult result = strategy.detect(blurry, sharp, 0.15);
        assertFalse(result.motionDetected(),
                "Blur-only changes should NOT be flagged as motion, "
                + "motionScore=" + result.motionScore());
    }

    @Test
    void realMotionShouldBeDetected() {
        // Shift the pattern by a non-period-multiple amount — simulates real subject motion.
        // Stride is 4 so one full period is 8 (dark+light); shift of 5 is not a multiple.
        byte[] original = generateCheckerboard(64, 64, 4);
        byte[] shifted = shiftImage(original, 64, 64, 5);

        MotionDetector.MotionResult result = strategy.detect(shifted, original, 0.15);
        assertTrue(result.motionDetected(),
                "Shifted image should be flagged as motion, "
                + "motionScore=" + result.motionScore());
    }

    @Test
    void nullFramesShouldNotDetectMotion() {
        MotionDetector.MotionResult result = strategy.detect(null, null, 0.15);
        assertFalse(result.motionDetected());
        assertEquals(0.0, result.motionScore(), 0.001);
    }

    @Test
    void tinyFramesShouldNotDetectMotion() {
        byte[] tiny = new byte[4]; // too small for Sobel
        MotionDetector.MotionResult result = strategy.detect(tiny, tiny, 0.15);
        assertFalse(result.motionDetected());
    }

    @Test
    void sobelMagnitudeProducesNonZeroForEdges() {
        byte[] data = generateCheckerboard(16, 16, 4);
        double[] mag = StructuralSimilarityStrategy.sobelMagnitude(data, 16, 16);
        double sum = 0;
        for (double v : mag) sum += v;
        assertTrue(sum > 0, "Sobel magnitude should be non-zero for a checkerboard");
    }

    @Test
    void normalizedCrossCorrelationOfIdenticalArraysIsOne() {
        double[] a = {1.0, 2.0, 3.0, 4.0};
        double ncc = StructuralSimilarityStrategy.normalizedCrossCorrelation(a, a);
        assertEquals(1.0, ncc, 0.001);
    }

    @Test
    void normalizedCrossCorrelationOfOppositeArraysIsZeroOrLess() {
        double[] a = {1.0, 0.0, 1.0, 0.0};
        double[] b = {0.0, 1.0, 0.0, 1.0};
        double ncc = StructuralSimilarityStrategy.normalizedCrossCorrelation(a, b);
        // Anti-correlated — clamped to 0
        assertTrue(ncc <= 0.01, "Opposite patterns should have low NCC, got " + ncc);
    }

    /** Generates a checkerboard pattern. */
    private static byte[] generateCheckerboard(int width, int height, int stride) {
        byte[] data = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean bright = ((x / stride + y / stride) % 2 == 0);
                data[y * width + x] = (byte) (bright ? 220 : 30);
            }
        }
        return data;
    }

    /**
     * Reduces contrast by blending each pixel towards the mean value.
     * Simulates defocus: edges stay in identical positions, only amplitude changes.
     */
    private static byte[] reduceContrast(byte[] src, double factor) {
        double mean = 0;
        for (byte b : src) mean += Byte.toUnsignedInt(b);
        mean /= src.length;

        byte[] dst = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            double v = Byte.toUnsignedInt(src[i]);
            dst[i] = (byte) Math.round(mean + (v - mean) * factor);
        }
        return dst;
    }

    /**
     * Shifts an image horizontally by the given number of pixels, wrapping around.
     * Simulates real subject motion.
     */
    private static byte[] shiftImage(byte[] src, int width, int height, int shiftX) {
        byte[] dst = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcX = (x + shiftX) % width;
                dst[y * width + x] = src[y * width + srcX];
            }
        }
        return dst;
    }
}

