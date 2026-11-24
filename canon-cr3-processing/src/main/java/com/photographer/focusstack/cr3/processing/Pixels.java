package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3Workers;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferUShort;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.function.IntUnaryOperator;

/** Direct raster access and row partitioning for color transforms and EXIF orientation. */
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

    static boolean spansWholeBuffer(BufferedImage image) {
        var raster = image.getRaster();
        var buffer = raster.getDataBuffer();
        return image.getClass() == BufferedImage.class
                && raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0
                && buffer.getOffset() == 0 && buffer.getSize() == (long) image.getWidth() * image.getHeight();
    }

    /** Returns distinct, unpadded banks in logical channel order, or null for other layouts. */
    static short[][] denseRgb16Bands(BufferedImage image) {
        var raster = image.getRaster();
        var buffer = raster.getDataBuffer();
        if (!spansWholeBuffer(image) || !(buffer instanceof DataBufferUShort shorts) || shorts.getNumBanks() != 3
                || !(raster.getSampleModel() instanceof BandedSampleModel layout)
                || layout.getNumBands() != 3 || layout.getScanlineStride() != image.getWidth()
                || image.getColorModel().getNumComponents() != 3) {
            return null;
        }
        int[] indices = layout.getBankIndices();
        int[] bandOffsets = layout.getBandOffsets();
        int[] dataOffsets = shorts.getOffsets();
        short[][] banks = shorts.getBankData();
        for (int band = 0; band < 3; band++) {
            if (indices[band] < 0 || indices[band] >= 3 || bandOffsets[band] != 0
                    || dataOffsets[band] != 0 || banks[band].length != buffer.getSize()
                    || image.getColorModel().getComponentSize(band) != 16) {
                return null;
            }
            for (int previous = 0; previous < band; previous++) {
                if (indices[previous] == indices[band] || banks[previous] == banks[band]) {
                    return null;
                }
            }
        }
        return new short[][]{banks[indices[0]], banks[indices[1]], banks[indices[2]]};
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
        if (packed != null) {
            transformPacked(packed, width, height, maxWorkers, (pixels, offset) -> {
                for (int i = offset; i < offset + width; i++) {
                    pixels[i] = transform.applyAsInt(pixels[i]);
                }
            });
            return;
        }
        Cr3Workers.count(maxWorkers, 1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, transform.applyAsInt(image.getRGB(x, y)));
            }
        }
    }

    @FunctionalInterface
    interface PackedRowTransform {
        void apply(int[] pixels, int offset);
    }

    static void transformPacked(Packed packed, int width, int height, int maxWorkers,
                                PackedRowTransform transform) {
        // Custom packed rasters can alias adjacent rows. Preserve row order in that case.
        int workItems = packed.stride() >= width ? maxStripes(width, height) : 1;
        int stripes = Cr3Workers.count(maxWorkers, workItems);
        int[] pixels = packed.data();
        Cr3Workers.run(stripes, stripe -> {
            int end = stripeStart(stripe + 1, height, stripes);
            for (int y = stripeStart(stripe, height, stripes); y < end; y++) {
                transform.apply(pixels, packed.rowStart(y));
            }
        });
    }
}
