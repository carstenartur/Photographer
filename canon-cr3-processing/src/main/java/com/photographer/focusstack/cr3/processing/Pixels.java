package com.photographer.focusstack.cr3.processing;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.function.IntUnaryOperator;

/** Packed sRGB pixel access for color transforms and EXIF orientation. */
final class Pixels {

    private Pixels() {
    }

    record Packed(int[] data, int offset, int stride) {
        int rowStart(int y) {
            return offset + y * stride;
        }
    }

    static Packed packed(BufferedImage image) {
        var raster = image.getRaster();
        // A packed linear-RGB image can also report TYPE_INT_RGB. Its color model
        // must perform the same conversions as getRGB/setRGB.
        if (image.getClass() == BufferedImage.class && image.getColorModel().getColorSpace().isCS_sRGB()
                && (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_INT_ARGB)
                && raster.getDataBuffer() instanceof DataBufferInt buffer
                && raster.getSampleModel() instanceof SinglePixelPackedSampleModel model) {
            int offset = buffer.getOffset() + model.getOffset(-raster.getSampleModelTranslateX(),
                    -raster.getSampleModelTranslateY());
            return new Packed(buffer.getData(), offset, model.getScanlineStride());
        }
        return null;
    }

    static void transform(BufferedImage image, IntUnaryOperator transform) {
        int width = image.getWidth();
        int height = image.getHeight();
        Packed packed = packed(image);
        if (packed != null) {
            int[] pixels = packed.data();
            for (int y = 0; y < height; y++) {
                int offset = packed.rowStart(y);
                for (int i = offset; i < offset + width; i++) {
                    pixels[i] = transform.applyAsInt(pixels[i]);
                }
            }
            return;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, transform.applyAsInt(image.getRGB(x, y)));
            }
        }
    }
}
