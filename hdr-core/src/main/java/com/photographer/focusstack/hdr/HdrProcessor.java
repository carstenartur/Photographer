package com.photographer.focusstack.hdr;

import java.util.List;

/**
 * Interface for merging multiple exposure-bracketed images into a single HDR image.
 * Implementations may use various algorithms (Debevec, Robertson, Mertens, etc.).
 * Concrete library dependencies are encapsulated in adapter classes.
 */
public interface HdrProcessor {

    /**
     * Merges multiple bracketed exposures into a single HDR image.
     *
     * @param bracketImages list of image byte arrays at different exposures
     * @param evOffsets     the EV offset for each image (same order as bracketImages)
     * @return the merged HDR image bytes
     */
    byte[] merge(List<byte[]> bracketImages, List<Double> evOffsets);

    /**
     * Returns the name of this HDR processor implementation.
     */
    String name();
}
