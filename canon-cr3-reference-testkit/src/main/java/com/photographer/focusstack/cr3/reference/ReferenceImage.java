package com.photographer.focusstack.cr3.reference;

import java.util.Objects;

/**
 * Single-band reference image represented by unsigned sample values in an
 * {@code int[]} array.
 *
 * <p>The sample array is intentionally not copied because full-resolution RAW
 * fixtures contain tens of millions of values. Callers must treat it as
 * read-only.</p>
 */
public record ReferenceImage(int width, int height, int[] samples) {

    public ReferenceImage {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid reference geometry: " + width + "x" + height);
        }
        Objects.requireNonNull(samples, "samples");
        long expected = (long) width * height;
        if (expected > Integer.MAX_VALUE || samples.length != (int) expected) {
            throw new IllegalArgumentException("Expected " + expected + " samples for "
                    + width + "x" + height + ", got " + samples.length);
        }
    }
}
