package com.photographer.focusstack.cr3.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.BitSet;

/**
 * Applies EXIF orientation transformations to an image.
 *
 * <p>EXIF orientation values 1-8 describe how the image data should be
 * rotated/flipped to match the camera's shooting orientation. This class
 * transforms the pixel data accordingly so the output image appears correctly
 * oriented for display.</p>
 *
 * <p>EXIF orientation values (per EXIF 2.3 specification):</p>
 * <pre>
 *   1 = Normal (no transformation)
 *   2 = Flip horizontal
 *   3 = Rotate 180°
 *   4 = Flip vertical
 *   5 = Transpose (flip horizontal + rotate 270° CW)
 *   6 = Rotate 90° CW
 *   7 = Transverse (flip horizontal + rotate 90° CW)
 *   8 = Rotate 270° CW (= 90° CCW)
 * </pre>
 *
 * <p>This transformation operates on the final output image only; the RAW Bayer
 * data is always stored in sensor orientation.</p>
 */
public final class ExifOrientationTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(ExifOrientationTransformer.class);

    private ExifOrientationTransformer() {}

    /**
     * Applies the EXIF orientation transformation to an image.
     *
     * @param image       the input image
     * @param orientation the EXIF orientation value (1-8)
     * @return the transformed image (may be a new image for rotations, or same for no-op)
     */
    public static BufferedImage apply(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            // 1 = normal, <1 or >8 = invalid → no transformation
            return image;
        }

        LOG.debug("Applying EXIF orientation {} to {}x{} image",
                orientation, image.getWidth(), image.getHeight());

        int width = image.getWidth();
        int height = image.getHeight();
        boolean transpose = orientation >= 5;
        boolean reverseLine = reversesX(orientation);
        boolean reverseRows = reversesY(orientation);
        int outputWidth = transpose ? height : width;
        int outputHeight = transpose ? width : height;
        var model = image.getColorModel();
        var output = new BufferedImage(model, image.getRaster().createCompatibleWritableRaster(outputWidth, outputHeight),
                image.isAlphaPremultiplied(), null);
        var packed = Pixels.packed(image);
        if (packed != null) {
            int[] dst = ((DataBufferInt) output.getRaster().getDataBuffer()).getData();
            // A source row becomes either a destination row or a destination column.
            int step = transpose ? height : 1;
            int start = reverseLine ? (width - 1) * step : 0;
            if (reverseLine) {
                step = -step;
            }
            int mask = image.getType() == BufferedImage.TYPE_INT_RGB ? 0xffffff : -1;
            // On rotation keep a small group of destination rows hot across source rows.
            int blockWidth = transpose ? 32 : width;
            for (int left = 0; left < width; left += blockWidth) {
                int right = Math.min(width, left + blockWidth);
                for (int y = 0; y < height; y++) {
                    int row = reverseRows ? height - 1 - y : y;
                    int to = (transpose ? row : row * width) + start + left * step;
                    int from = packed.rowStart(y) + left;
                    for (int x = left; x < right; x++, from++, to += step) {
                        dst[to] = packed.data()[from] & mask;
                    }
                }
            }
        } else {
            Object pixel = null;
            for (int y = 0; y < height; y++) {
                int row = reverseRows ? height - 1 - y : y;
                for (int x = 0; x < width; x++) {
                    int col = reverseLine ? width - 1 - x : x;
                    pixel = image.getRaster().getDataElements(x, y, pixel);
                    output.getRaster().setDataElements(transpose ? row : col, transpose ? col : row, pixel);
                }
            }
        }
        return output;
    }

    /**
     * Orients an exclusively owned image. Flips and 180-degree rotation reuse a packed
     * image's storage. The caller must use the returned image when the axes are exchanged.
     *
     * @param image the input image whose pixels may be overwritten
     * @param orientation the EXIF orientation value (1-8)
     * @return the oriented image
     */
    public static BufferedImage applyToOwnedImage(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 4) {
            return apply(image, orientation);
        }
        var packed = Pixels.packed(image);
        int width = image.getWidth();
        if (packed == null || packed.stride() < width) {
            return apply(image, orientation);
        }

        int height = image.getHeight();
        int[] data = packed.data();
        if (orientation == 2) {
            for (int y = 0; y < height; y++) {
                reverseRow(data, packed.rowStart(y), width);
            }
        } else {
            for (int y = 0; y < height / 2; y++) {
                int top = packed.rowStart(y);
                int bottom = packed.rowStart(height - 1 - y);
                for (int x = 0; x < width; x++) {
                    swap(data, top + x, bottom + (orientation == 3 ? width - 1 - x : x));
                }
            }
            if (orientation == 3 && (height & 1) != 0) {
                reverseRow(data, packed.rowStart(height / 2), width);
            }
        }
        return image;
    }

    /**
     * Orients exclusively owned dense RGB storage without allocating another image-sized pixel array.
     * Packed sRGB integer images and three-bank unsigned-short RGB16 images retain their original
     * arrays. Flips and 180-degree rotation use constant scratch space; axis-swapping orientations
     * use one visited bit per pixel and return a new raster over the same arrays.
     *
     * <p>Compact storage must have zero offsets, no row padding, and exactly one image's samples
     * in each distinct bank. Other layouts, including subimages and aliased banks, delegate to
     * {@link #applyToOwnedImage} and may allocate a separate image. Identity and invalid EXIF
     * orientations return the original image unchanged.</p>
     *
     * <p>The caller relinquishes exclusive ownership of the pixels. Use only the returned image:
     * the original raster has obsolete dimensions when the axes are exchanged. A failed
     * transformation can leave partially rearranged pixels.</p>
     *
     * @param image exclusively owned input image
     * @param orientation EXIF orientation (1-8)
     * @return oriented image, sharing the original pixel arrays for supported dense layouts
     */
    public static BufferedImage applyToOwnedImageCompact(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        DensePixels pixels = densePixels(image);
        if (pixels == null) {
            return applyToOwnedImage(image, orientation);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        // densePixels verifies that this product fits an array index before any mutation.
        int count = Math.multiplyExact(width, height);
        if (orientation == 2) {
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width / 2; x++) {
                    pixels.swap(row + x, row + width - 1 - x);
                }
            }
        } else if (orientation == 3) {
            for (int i = 0; i < count / 2; i++) {
                pixels.swap(i, count - 1 - i);
            }
        } else if (orientation == 4) {
            for (int y = 0; y < height / 2; y++) {
                int top = y * width;
                int bottom = (height - 1 - y) * width;
                for (int x = 0; x < width; x++) {
                    pixels.swap(top + x, bottom + x);
                }
            }
        } else {
            var output = pixels.transposedImage(image);
            var visited = new BitSet(count);
            boolean reverseX = reversesX(orientation);
            boolean reverseY = reversesY(orientation);
            for (int first = visited.nextClearBit(0); first < count; first = visited.nextClearBit(first + 1)) {
                visited.set(first);
                int next = transposedIndex(first, width, height, reverseX, reverseY);
                // Swapping against the first site rotates the entire cycle in source-to-destination order.
                // All channels move together, so one visited set suffices for RGB16 as well as packed RGB.
                while (next != first) {
                    pixels.swap(first, next);
                    visited.set(next);
                    next = transposedIndex(next, width, height, reverseX, reverseY);
                }
            }
            return output;
        }
        return image;
    }

    private static int transposedIndex(int source, int width, int height, boolean reverseX, boolean reverseY) {
        int x = source % width;
        int y = source / width;
        int column = reverseX ? width - 1 - x : x;
        int row = reverseY ? height - 1 - y : y;
        return (int) ((long) column * height + row);
    }

    private static DensePixels densePixels(BufferedImage image) {
        long count = (long) image.getWidth() * image.getHeight();
        var raster = image.getRaster();
        var buffer = raster.getDataBuffer();
        if (image.getClass() != BufferedImage.class || count > Integer.MAX_VALUE
                || raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0
                || buffer.getOffset() != 0 || buffer.getSize() != count) {
            return null;
        }
        var packed = Pixels.packed(image);
        if (packed != null && buffer.getNumBanks() == 1 && packed.offset() == 0
                && packed.stride() == image.getWidth() && packed.data().length == count) {
            return new DensePixels(packed.data(), null);
        }
        if (!(buffer instanceof DataBufferUShort shorts) || shorts.getNumBanks() != 3
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
                    || dataOffsets[band] != 0 || banks[band].length != count
                    || image.getColorModel().getComponentSize(band) != 16) {
                return null;
            }
            for (int previous = 0; previous < band; previous++) {
                if (indices[previous] == indices[band] || banks[previous] == banks[band]) {
                    return null;
                }
            }
        }
        return new DensePixels(null, banks);
    }

    private record DensePixels(int[] packed, short[][] banks) {
        void swap(int first, int second) {
            if (packed != null) {
                ExifOrientationTransformer.swap(packed, first, second);
            } else {
                for (short[] bank : banks) {
                    short value = bank[first];
                    bank[first] = bank[second];
                    bank[second] = value;
                }
            }
        }

        BufferedImage transposedImage(BufferedImage image) {
            var source = image.getRaster();
            var buffer = source.getDataBuffer();
            int width = image.getHeight();
            int height = image.getWidth();
            var raster = packed != null
                    ? Raster.createPackedRaster(buffer, width, height, width,
                            ((SinglePixelPackedSampleModel) source.getSampleModel()).getBitMasks(), null)
                    : Raster.createBandedRaster(buffer, width, height, width,
                            ((BandedSampleModel) source.getSampleModel()).getBankIndices(), new int[3], null);
            return new BufferedImage(image.getColorModel(), raster, image.isAlphaPremultiplied(), null);
        }
    }

    private static void reverseRow(int[] data, int rowStart, int width) {
        for (int x = 0; x < width / 2; x++) {
            swap(data, rowStart + x, rowStart + width - 1 - x);
        }
    }

    private static void swap(int[] data, int first, int second) {
        int value = data[first];
        data[first] = data[second];
        data[second] = value;
    }

    /**
     * Returns the output dimensions after applying EXIF orientation.
     *
     * @param width       input width
     * @param height      input height
     * @param orientation EXIF orientation (1-8)
     * @return int[2] with {outputWidth, outputHeight}
     */
    public static int[] outputDimensions(int width, int height, int orientation) {
        if (orientation >= 5 && orientation <= 8) {
            // Orientations 5-8 swap width and height
            return new int[]{height, width};
        }
        return new int[]{width, height};
    }

    /** Maps a rectangle in oriented output coordinates back into the unoriented image. */
    public static Rectangle sourceRegion(Rectangle output, int width, int height, int orientation) {
        boolean transpose = orientation >= 5 && orientation <= 8;
        int x = transpose ? output.y : output.x;
        int y = transpose ? output.x : output.y;
        int w = transpose ? output.height : output.width;
        int h = transpose ? output.width : output.height;
        return new Rectangle(reversesX(orientation) ? width - x - w : x,
                reversesY(orientation) ? height - y - h : y, w, h);
    }

    static boolean reversesX(int orientation) {
        return orientation == 2 || orientation == 3 || orientation == 7 || orientation == 8;
    }

    static boolean reversesY(int orientation) {
        return orientation == 3 || orientation == 4 || orientation == 6 || orientation == 7;
    }
}
