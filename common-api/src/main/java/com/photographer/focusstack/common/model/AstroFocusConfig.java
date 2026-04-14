package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;

/** Focus configuration for an astrophotography session. */
public record AstroFocusConfig(
        @JsonProperty("strategy") AstroFocusStrategy strategy,
        @JsonProperty("scanSteps") @PositiveOrZero int scanSteps,
        @JsonProperty("scanStepSize") @PositiveOrZero int scanStepSize,
        @JsonProperty("initialFocusPosition") Integer initialFocusPosition,
        @JsonProperty("bestFocusPosition") Integer bestFocusPosition
) {
    public static AstroFocusConfig manualFixed() {
        return new AstroFocusConfig(AstroFocusStrategy.MANUAL_FIXED, 0, 0, null, null);
    }

    public static AstroFocusConfig focusScan(int steps, int stepSize) {
        return new AstroFocusConfig(AstroFocusStrategy.FOCUS_SCAN, steps, stepSize, null, null);
    }
}
