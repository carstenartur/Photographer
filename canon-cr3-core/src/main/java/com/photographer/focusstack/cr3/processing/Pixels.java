package com.photographer.focusstack.cr3.processing;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Utility for obtaining int-packed pixel buffers in image-processing hot paths.
 */
final class Pixels {

    private Pixels() {
    }

    /**
     * Returns an int-packed buffer for the image, converting once to {@code TYPE_INT_ARGB} when needed.
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
