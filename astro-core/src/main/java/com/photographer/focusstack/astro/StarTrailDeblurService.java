package com.photographer.focusstack.astro;

import java.awt.image.BufferedImage;

/**
 * Interface for future star-trail deblurring.
 * Prepares the architecture for single-frame star-trail deblur without implementing it yet.
 */
public interface StarTrailDeblurService {

    /**
     * Attempts to deblur star trails in a stacked image.
     * @param image the stacked astronomical image
     * @return the processed image (may be unchanged for no-op implementations)
     */
    BufferedImage deblur(BufferedImage image);

    /**
     * Returns true if this implementation actually performs deblurring.
     */
    boolean isEnabled();
}
