package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

/** Configuration for astronomical frame combination. */
public record AstroStackingConfig(
        @JsonProperty("mode") AstroStackingMode mode,
        @JsonProperty("sigmaThreshold") @DecimalMin("0.1") double sigmaThreshold,
        @JsonProperty("maxIterations") @Min(1) int maxIterations,
        @JsonProperty("minimumSamples") @Min(3) int minimumSamples
) {
    public static final double DEFAULT_SIGMA_THRESHOLD = 2.5;
    public static final int DEFAULT_MAX_ITERATIONS = 3;
    public static final int DEFAULT_MINIMUM_SAMPLES = 4;

    public AstroStackingConfig {
        mode = mode == null ? AstroStackingMode.MEAN : mode;
        if (!Double.isFinite(sigmaThreshold) || sigmaThreshold < 0.1) {
            throw new IllegalArgumentException("sigmaThreshold must be finite and at least 0.1");
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1");
        }
        if (minimumSamples < 3) {
            throw new IllegalArgumentException("minimumSamples must be at least 3");
        }
    }

    public static AstroStackingConfig defaults() {
        return new AstroStackingConfig(
                AstroStackingMode.MEAN,
                DEFAULT_SIGMA_THRESHOLD,
                DEFAULT_MAX_ITERATIONS,
                DEFAULT_MINIMUM_SAMPLES);
    }

    public static AstroStackingConfig median() {
        return new AstroStackingConfig(
                AstroStackingMode.MEDIAN,
                DEFAULT_SIGMA_THRESHOLD,
                DEFAULT_MAX_ITERATIONS,
                DEFAULT_MINIMUM_SAMPLES);
    }

    public static AstroStackingConfig sigmaClipped(
            double sigmaThreshold,
            int maxIterations,
            int minimumSamples) {
        return new AstroStackingConfig(
                AstroStackingMode.SIGMA_CLIPPED_MEAN,
                sigmaThreshold,
                maxIterations,
                minimumSamples);
    }
}
