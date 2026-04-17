package com.photographer.focusstack.analysis.service;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Strategy interface for merging a focus stack into a single all-in-focus image.
 *
 * <p>Different implementations may use Z-projection, Extended Depth of Field
 * algorithms, or external tools (e.g. headless Fiji).
 */
public interface StackMerger {

    /**
     * Merges the supplied frames into a single all-in-focus image.
     *
     * @param frames ordered list of raw frame byte arrays (e.g. PNG or JPEG)
     * @return the merged image as a byte array (PNG format)
     * @throws IllegalArgumentException if the frames list is null or empty
     */
    byte[] merge(List<byte[]> frames);

    /**
     * Merges pre-decoded {@link BufferedImage} frames into a single all-in-focus image.
     *
     * <p>This is the preferred entry point for RAW workflows where the caller
     * has already decoded CR3 (or other RAW) files into {@code BufferedImage}
     * objects.  It avoids a lossy JPEG/PNG round-trip and preserves the full
     * colour depth of the source images.
     *
     * @param images ordered list of decoded frames (all must have identical dimensions)
     * @return the merged all-in-focus image
     * @throws IllegalArgumentException if the list is null, empty, or dimensions differ
     */
    BufferedImage mergeImages(List<BufferedImage> images);

    /** Human-readable name of this merger implementation. */
    String name();
}
