package com.photographer.focusstack.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AstroStackingConfigTest {

    @Test
    void defaultsPreserveHistoricalMeanMode() {
        AstroStackingConfig config = AstroStackingConfig.defaults();

        assertEquals(AstroStackingMode.MEAN, config.mode());
        assertEquals(AstroStackingConfig.DEFAULT_SIGMA_THRESHOLD, config.sigmaThreshold());
        assertEquals(AstroStackingConfig.DEFAULT_MAX_ITERATIONS, config.maxIterations());
        assertEquals(AstroStackingConfig.DEFAULT_MINIMUM_SAMPLES, config.minimumSamples());
    }

    @Test
    void nullModeNormalizesToMeanForLegacyConfiguration() {
        AstroStackingConfig config = new AstroStackingConfig(null, 2.0, 2, 3);

        assertEquals(AstroStackingMode.MEAN, config.mode());
    }

    @Test
    void oldCaptureConstructorsPreserveMeanMode() {
        AstroCaptureConfig withProfile = new AstroCaptureConfig(
                "camera", 5, 1000, 800, "f/4", 0,
                "PNG", false, true, false);
        AstroCaptureConfig defaultProfile = new AstroCaptureConfig(
                5, 1000, 800, "f/4", 0,
                "PNG", false, true, false);

        assertEquals(AstroStackingConfig.defaults(), withProfile.stackingConfig());
        assertEquals(AstroStackingConfig.defaults(), defaultProfile.stackingConfig());
    }

    @Test
    void nullCaptureStackingConfigNormalizesToMean() {
        AstroCaptureConfig capture = new AstroCaptureConfig(
                "camera", 5, 1000, 800, "f/4", 0,
                "PNG", false, true, false, null);

        assertEquals(AstroStackingConfig.defaults(), capture.stackingConfig());
    }

    @Test
    void explicitCaptureStackingConfigIsRetained() {
        AstroStackingConfig expected = AstroStackingConfig.sigmaClipped(1.8, 4, 5);
        AstroCaptureConfig capture = new AstroCaptureConfig(
                "camera", 5, 1000, 800, "f/4", 0,
                "PNG", false, true, false, expected);

        assertEquals(expected, capture.stackingConfig());
    }

    @Test
    void rejectsInvalidSigmaThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> new AstroStackingConfig(AstroStackingMode.SIGMA_CLIPPED_MEAN,
                        0.0, 3, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new AstroStackingConfig(AstroStackingMode.SIGMA_CLIPPED_MEAN,
                        Double.NaN, 3, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new AstroStackingConfig(AstroStackingMode.SIGMA_CLIPPED_MEAN,
                        Double.POSITIVE_INFINITY, 3, 4));
    }

    @Test
    void rejectsInvalidIterationAndSampleLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new AstroStackingConfig(AstroStackingMode.SIGMA_CLIPPED_MEAN,
                        2.5, 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new AstroStackingConfig(AstroStackingMode.SIGMA_CLIPPED_MEAN,
                        2.5, 3, 2));
    }
}
