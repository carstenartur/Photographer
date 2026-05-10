package com.photographer.focusstack.astro;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Pixel-buffer helper for fast int-packed image access in astro hot paths.
 */
final class Pixels {

    private Pixels() {
    }

    /**
     * Returns an int-packed ARGB buffer for the provided image.
     *
     * <p>If the image already uses an int-packed raster ({@code TYPE_INT_RGB} or {@code TYPE_INT_ARGB}),
     * this returns the backing array directly. Otherwise, the image is copied once to {@code TYPE_INT_ARGB}.</p>
     */
    static int[] intBufferOf(BufferedImage image) {
        if ((image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_INT_ARGB)
                && image.getRaster().getDataBuffer() instanceof DataBufferInt dataBufferInt) {
            return dataBufferInt.getData();
        }
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = converted.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return ((DataBufferInt) converted.getRaster().getDataBuffer()).getData();
    }
}
