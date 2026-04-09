package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingParameters;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StackingAdvisorTest {

    // Use structural-similarity strategy (the new default)
    private final StackingAdvisor advisor = new StackingAdvisor(
            new SharpnessAnalyzer(),
            new MotionDetector(),
            new InformationGainEstimator(),
            new ImageJProcessor(),
            "structural-similarity"
    );

    // Pixel-diff based advisor for verifying backward-compat strategy selection
    private final StackingAdvisor pixelDiffAdvisor = new StackingAdvisor(
            new SharpnessAnalyzer(),
            new MotionDetector(),
            new InformationGainEstimator(),
            new ImageJProcessor(),
            "pixel-diff"
    );

    @Test
    void firstFrameShouldRecommendContinue() {
        byte[] image = new byte[100];
        for (int i = 0; i < 100; i++) {
            image[i] = (byte) (i % 256);
        }

        StackingParameters params = new StackingParameters(0, 1, 30, 0.01, true, QualityMode.BALANCED);

        AnalysisResult result = advisor.analyzeFrame(image, null, Collections.emptyList(), params);
        assertNotNull(result);
        assertTrue(result.recommendContinue(), "First frame should recommend continue");
    }

    @Test
    void shouldStopWhenMaxFramesReached() {
        byte[] image = new byte[100];
        StackingParameters params = new StackingParameters(0, 1, 2, 0.01, false, QualityMode.BALANCED);

        List<AnalysisResult> history = new ArrayList<>();
        history.add(new AnalysisResult("1", 0.5, 0.3, false, 0.0, 0.5, true));
        history.add(new AnalysisResult("2", 0.6, 0.4, false, 0.0, 0.3, true));

        AnalysisResult result = advisor.analyzeFrame(image, null, history, params);
        assertFalse(result.recommendContinue(), "Should stop when max frames reached");
    }

    @Test
    void shouldStopOnRealMotion() {
        // Shift the pattern by a non-period-multiple — simulates real subject movement.
        byte[] original = generateCheckerboard(64, 64, 4);
        byte[] shifted = shiftImage(original, 64, 64, 5);

        StackingParameters params = new StackingParameters(0, 1, 30, 0.01, true, QualityMode.BALANCED);

        AnalysisResult result = advisor.analyzeFrame(shifted, original, Collections.emptyList(), params);
        assertTrue(result.motionDetected(),
                "Structural-similarity strategy should detect real motion (shifted edges)");
        assertFalse(result.recommendContinue(),
                "Should stop when real motion is detected");
    }

    @Test
    void shouldContinueWhenOnlyBlurChanges() {
        // Same pattern with reduced contrast simulates defocus blur —
        // edges are in identical positions but weaker.
        byte[] sharp = generateCheckerboard(64, 64, 4);
        byte[] blurry = reduceContrast(sharp, 0.5);

        StackingParameters params = new StackingParameters(0, 1, 30, 0.01, true, QualityMode.BALANCED);

        AnalysisResult result = advisor.analyzeFrame(blurry, sharp, Collections.emptyList(), params);
        assertTrue(result.recommendContinue(),
                "Structural-similarity strategy should not stop on focus-change-only differences");
    }

    @Test
    void pixelDiffStrategyCanBeSelected() {
        // With pixel-diff, two completely different frames should detect motion
        byte[] frame1 = new byte[100];
        byte[] frame2 = new byte[100];
        java.util.Arrays.fill(frame1, (byte) 0);
        java.util.Arrays.fill(frame2, (byte) 255);

        StackingParameters params = new StackingParameters(0, 1, 30, 0.01, true, QualityMode.BALANCED);

        AnalysisResult result = pixelDiffAdvisor.analyzeFrame(frame2, frame1, Collections.emptyList(), params);
        assertTrue(result.motionDetected(), "Pixel-diff strategy should detect totally different frames");
    }

    /** Generates a simple checkerboard pattern image. */
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

    /** Shifts an image horizontally, wrapping around — simulates real subject motion. */
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

