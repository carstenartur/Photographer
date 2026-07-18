package com.photographer.focusstack.cr3.reference;

import java.util.Objects;

/**
 * A single-band integer reference image used by decoder validation.
 *
 * <p>The sample array is row-major and must contain exactly
 * {@code width * height} entries.</p>
 *
 * @param width image width
 * @param height image height
 * @param samples row-major unsigned sample values
 */
public record ReferenceImage(int width, int height, int[] samples) {

    public ReferenceImage {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Reference dimensions must be positive: " + width + "x" + height);
        }
        Objects.requireNonNull(samples, "samples");
        int expected = Math.multiplyExact(width, height);
        if (samples.length != expected) {
            throw new IllegalArgumentException(
                    "Reference sample count " + samples.length + " does not match " + width + "x" + height
                            + " (expected " + expected + ")");
        }
    }
}
