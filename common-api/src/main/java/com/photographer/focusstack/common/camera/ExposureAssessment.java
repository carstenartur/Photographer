package com.photographer.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of analysing a captured frame's exposure.
 *
 * @param meanBrightness    average brightness 0.0–1.0 (0 = black, 1 = white)
 * @param overexposedPct    percentage of pixels above the highlight threshold (0–100)
 * @param underexposedPct   percentage of pixels below the shadow threshold (0–100)
 * @param verdict           overall assessment
 * @param message           human-readable explanation
 */
public record ExposureAssessment(
        @JsonProperty("meanBrightness") double meanBrightness,
        @JsonProperty("overexposedPct") double overexposedPct,
        @JsonProperty("underexposedPct") double underexposedPct,
        @JsonProperty("verdict") Verdict verdict,
        @JsonProperty("message") String message
) {

    public enum Verdict {
        /** Exposure looks fine — proceed. */
        OK,
        /** Slightly off but still usable. */
        WARN,
        /** Severely mis-exposed — should abort or adjust settings. */
        CRITICAL
    }

    public boolean isAcceptable() {
        return verdict != Verdict.CRITICAL;
    }
}
