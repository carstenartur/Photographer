package com.photographer.focusstack.analysis.service;

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

    /** Human-readable name of this merger implementation. */
    String name();
}
