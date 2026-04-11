package com.photographer.focusstack.hdr;

/**
 * Interface for tonemapping HDR images to displayable LDR images.
 * Implementations may use Reinhard, Drago, Mantiuk, or other algorithms.
 */
public interface Tonemapper {

    /**
     * Applies tonemapping to an HDR image.
     *
     * @param hdrImage the HDR image bytes
     * @param gamma    the gamma value for tonemapping
     * @return the tonemapped LDR image bytes
     */
    byte[] tonemap(byte[] hdrImage, double gamma);

    /**
     * Returns the name of this tonemapper implementation.
     */
    String name();
}
