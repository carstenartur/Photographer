package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.Cr3Workers;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.function.IntUnaryOperator;

/** Packed sRGB pixel access for color transforms and EXIF orientation. */
final class Pixels {

    private static final int MIN_PIXELS_PER_STRIPE = 256 * 1024;
    // Demosaicing needs up to three temporary rows per stripe.
    private static final int MIN_ROWS_PER_STRIPE = 64;

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

    static int maxStripes(int width, int height) {
        long byPixels = (long) width * height / MIN_PIXELS_PER_STRIPE;
        return (int) Math.max(1, Math.min(height / MIN_ROWS_PER_STRIPE, byPixels));
    }

    static int stripeStart(int stripe, int height, int stripes) {
        return (int) ((long) stripe * height / stripes);
    }

    static void transform(BufferedImage image, int maxWorkers, IntUnaryOperator transform) {
        int width = image.getWidth();
        int height = image.getHeight();
        Packed packed = packed(image);
        // Custom packed rasters can alias adjacent rows. Preserve row order in that case.
        int workItems = packed != null && packed.stride() >= width ? maxStripes(width, height) : 1;
        int stripes = Cr3Workers.count(maxWorkers, workItems);
        if (packed != null) {
            int[] pixels = packed.data();
            Cr3Workers.run(stripes, stripe -> {
                int end = stripeStart(stripe + 1, height, stripes);
                for (int y = stripeStart(stripe, height, stripes); y < end; y++) {
                    int offset = packed.rowStart(y);
                    for (int i = offset; i < offset + width; i++) {
                        pixels[i] = transform.applyAsInt(pixels[i]);
                    }
                }
            });
            return;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, transform.applyAsInt(image.getRGB(x, y)));
            }
        }
    }
}
