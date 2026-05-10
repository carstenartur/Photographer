package com.photographer.focusstack.cr3.processing;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Utility for obtaining int-packed pixel buffers in image-processing hot paths.
 */
final class Pixels {

    private Pixels() {
    }

    /** Returns an int-packed buffer backed by the provided image instance. */
    static int[] intBufferOf(BufferedImage image) {
        if ((image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_INT_ARGB)
                && image.getRaster().getDataBuffer() instanceof DataBufferInt dataBufferInt) {
            return dataBufferInt.getData();
        }
        throw new IllegalArgumentException(
                "BufferedImage must use TYPE_INT_RGB or TYPE_INT_ARGB for direct int-buffer access");
    }
}
