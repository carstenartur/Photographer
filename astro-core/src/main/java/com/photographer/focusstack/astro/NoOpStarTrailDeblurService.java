package com.photographer.focusstack.astro;

import java.awt.image.BufferedImage;

/**
 * No-op implementation of {@link StarTrailDeblurService}.
 * Returns the image unchanged. Placeholder for future deblurring logic.
 */
public class NoOpStarTrailDeblurService implements StarTrailDeblurService {

    @Override
    public BufferedImage deblur(BufferedImage image) {
        return image;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
