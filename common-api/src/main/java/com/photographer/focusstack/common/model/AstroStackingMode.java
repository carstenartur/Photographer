package com.photographer.focusstack.common.model;

/** Scientifically explicit pixel-combination mode for registered astro frames. */
public enum AstroStackingMode {
    /** Arithmetic mean with the historical integer-floor behavior. */
    MEAN,

    /** Per-channel median; an even sample count averages the two middle values. */
    MEDIAN,

    /** Iterative per-pixel RGB sigma rejection followed by an arithmetic mean. */
    SIGMA_CLIPPED_MEAN
}
