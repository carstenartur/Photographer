package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3Workers;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;

/**
 * Demosaics a Bayer-pattern RAW image into a full-color RGB image.
 *
 * <p>Uses bilinear interpolation. Supports all four standard Bayer patterns:
 * RGGB, BGGR, GRBG, GBRG.</p>
 *
 * <p>The Canon PowerShot G5 X Mark II uses an RGGB Bayer pattern.</p>
 */
public final class BayerDemosaicer {

    private static final Logger LOG = LoggerFactory.getLogger(BayerDemosaicer.class);

    private BayerDemosaicer() {
        // utility class
    }

    /**
     * Demosaics a Bayer-pattern RAW image using bilinear interpolation (8-bit output).
     *
     * <p>Uses {@code rawImage.maxValue()} as the white level for normalization.
     * When the pipeline has already subtracted black levels, pass an explicit
     * {@code whiteLevel} using {@link #demosaic(Cr3RawImage, int)} to avoid
     * a small green-channel under-exposure in the output.</p>
     *
     * @param rawImage the RAW Bayer image
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB, 8 bits per channel)
     */
    public static BufferedImage demosaic(Cr3RawImage rawImage) {
        return demosaic(rawImage, rawImage.maxValue());
    }

    /**
     * Demosaics a Bayer-pattern RAW image using bilinear interpolation (8-bit output),
     * normalizing pixel values against an explicit {@code whiteLevel}.
     *
     * <p>After black-level subtraction and white-balance, the effective maximum
     * for the (unscaled) green channel is {@code maxVal - blackLevel}, which is
     * lower than {@code maxValue()}.  Passing {@code whiteLevel = maxVal - minBL}
     * corrects this so that sensor saturation maps to 255 in all channels.</p>
     *
     * @param rawImage   the RAW Bayer image
     * @param whiteLevel the value that should map to 255 in the 8-bit output
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB, 8 bits per channel)
     */
    public static BufferedImage demosaic(Cr3RawImage rawImage, int whiteLevel) {
        LOG.info("Demosaicing {}x{} {} image with bilinear interpolation (whiteLevel={})",
                rawImage.width(), rawImage.height(), rawImage.bayerPattern(), whiteLevel);

        return demosaicToNewImage(rawImage, Math.max(1, whiteLevel));
    }

    /**
     * Demosaics into the supplied sensor buffer and uses it as the returned image's storage.
     * The caller must own the buffer exclusively. Its Bayer samples are replaced by packed RGB.
     *
     * @param rawImage the Bayer image whose storage may be overwritten
     * @param whiteLevel the value that should map to 255 in the 8-bit output
     * @return a TYPE_INT_RGB image backed by {@code rawImage.data()}
     */
    public static BufferedImage demosaicInPlace(Cr3RawImage rawImage, int whiteLevel) {
        return demosaicInPlace(rawImage, whiteLevel, 1);
    }

    /**
     * Demosaics in place, dividing large images into independently processed row stripes.
     *
     * @param rawImage the exclusively owned Bayer image whose storage may be overwritten
     * @param whiteLevel the value that should map to 255 in the 8-bit output
     * @param maxWorkers maximum number of workers, or zero for automatic selection
     * @return a TYPE_INT_RGB image backed by {@code rawImage.data()}
     */
    public static BufferedImage demosaicInPlace(Cr3RawImage rawImage, int whiteLevel, int maxWorkers) {
        int width = rawImage.width();
        int height = rawImage.height();
        int stripes = Cr3Workers.count(maxWorkers, Pixels.maxStripes(width, height));
        int[] data = rawImage.data();
        var buffer = new DataBufferInt(data, data.length);
        var model = new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
        var raster = Raster.createPackedRaster(buffer, width, height, width,
                new int[]{model.getRedMask(), model.getGreenMask(), model.getBlueMask()}, null);
        var output = new BufferedImage(model, raster, false, null);

        int effectiveWhite = Math.max(1, whiteLevel);
        if (stripes == 1) {
            writeRows(rawImage, effectiveWhite, 0, height);
            return output;
        }

        // Interpolation reads one row above and below. Keep each seam's Bayer samples
        // intact for both adjacent stripes, saving its RGB result until after the join.
        int[][] seams = new int[stripes - 1][width];
        for (int stripe = 1; stripe < stripes; stripe++) {
            int seam = Pixels.stripeStart(stripe, height, stripes);
            interpolateRow(rawImage, seam, effectiveWhite, seams[stripe - 1], 0);
        }
        Cr3Workers.run(stripes, stripe -> {
            int first = stripe == 0 ? 0 : Pixels.stripeStart(stripe, height, stripes) + 1;
            int end = Pixels.stripeStart(stripe + 1, height, stripes);
            writeRows(rawImage, effectiveWhite, first, end);
        });
        for (int stripe = 1; stripe < stripes; stripe++) {
            int seam = Pixels.stripeStart(stripe, height, stripes);
            System.arraycopy(seams[stripe - 1], 0, data, seam * width, width);
        }
        return output;
    }

    private static void writeRows(Cr3RawImage rawImage, int whiteLevel, int first, int end) {
        int width = rawImage.width();
        int[] data = rawImage.data();
        int[] previous = new int[width];
        int[] current = new int[width];
        // Row y-1 is still needed as input until interpolation of row y finishes.
        for (int y = first; y < end; y++) {
            interpolateRow(rawImage, y, whiteLevel, current, 0);
            if (y > first) {
                System.arraycopy(previous, 0, data, (y - 1) * width, width);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        System.arraycopy(previous, 0, data, (end - 1) * width, width);
    }

    /**
     * Demosaics a Bayer-pattern RAW image to a normalized 8-bit-per-channel BufferedImage.
     *
     * <p>This method scales the raw sensor values (which may be 14-bit or higher) down to
     * the 8-bit-per-channel range (0–255) required by {@link BufferedImage#TYPE_INT_RGB}.
     * For true high-bit-depth processing, callers should work with the raw {@code int[][]}
     * planes from {@link Cr3RawImage} directly.</p>
     *
     * @param rawImage the RAW Bayer image
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB, 8-bit per channel)
     */
    public static BufferedImage demosaicNormalized(Cr3RawImage rawImage) {
        LOG.info("Demosaicing {}x{} {} image to normalized 8-bit with bilinear interpolation",
                rawImage.width(), rawImage.height(), rawImage.bayerPattern());

        return demosaicToNewImage(rawImage, rawImage.maxValue());
    }

    private static BufferedImage demosaicToNewImage(Cr3RawImage rawImage, int whiteLevel) {
        int width = rawImage.width();
        var output = new BufferedImage(width, rawImage.height(), BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) output.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < rawImage.height(); y++) {
            interpolateRow(rawImage, y, whiteLevel, pixels, y * width);
        }
        return output;
    }

    /**
     * @deprecated Use {@link #demosaicNormalized(Cr3RawImage)} instead.
     *             This method was misleadingly named "16bit" but actually produces 8-bit output.
     */
    @Deprecated(since = "0.1.4", forRemoval = true)
    public static BufferedImage demosaic16bit(Cr3RawImage rawImage) {
        return demosaicNormalized(rawImage);
    }

    private static void interpolateRow(Cr3RawImage image, int y, int whiteLevel, int[] output, int offset) {
        int width = image.width();
        boolean evenRow = (y & 1) == 0;
        PixelColor first = getPixelColor(image.bayerPattern(), evenRow, true);
        PixelColor second = getPixelColor(image.bayerPattern(), evenRow, false);
        int rowStart = y * width;
        for (int x = 0; x < width; x++) {
            PixelColor color = (x & 1) == 0 ? first : second;
            if (x > 0 && x < width - 1 && y > 0 && y < image.height() - 1) {
                output[offset + x] = interiorPixel(image.data(), rowStart + x, width, color, whiteLevel);
            } else {
                output[offset + x] = borderPixel(image, x, y, color, whiteLevel);
            }
        }
    }

    private static int interiorPixel(int[] data, int i, int width, PixelColor color, int whiteLevel) {
        int horizontal = data[i - 1] + data[i + 1];
        int vertical = data[i - width] + data[i + width];
        int center = data[i];
        if (color == PixelColor.GREEN_IN_RED_ROW) {
            return packRgb(horizontal / 2, center, vertical / 2, whiteLevel);
        }
        if (color == PixelColor.GREEN_IN_BLUE_ROW) {
            return packRgb(vertical / 2, center, horizontal / 2, whiteLevel);
        }
        int cross = (horizontal + vertical) / 4;
        int diagonal = (data[i - width - 1] + data[i - width + 1]
                + data[i + width - 1] + data[i + width + 1]) / 4;
        return color == PixelColor.RED
                ? packRgb(center, cross, diagonal, whiteLevel)
                : packRgb(diagonal, cross, center, whiteLevel);
    }

    private static int borderPixel(Cr3RawImage img, int x, int y, PixelColor color, int whiteLevel) {
        int r, g, b;
        switch (color) {
            case RED -> {
                r = img.getPixel(x, y);
                g = averageCross(img, x, y);
                b = averageDiagonal(img, x, y);
            }
            case GREEN_IN_RED_ROW -> {
                r = averageHorizontal(img, x, y);
                g = img.getPixel(x, y);
                b = averageVertical(img, x, y);
            }
            case GREEN_IN_BLUE_ROW -> {
                r = averageVertical(img, x, y);
                g = img.getPixel(x, y);
                b = averageHorizontal(img, x, y);
            }
            case BLUE -> {
                r = averageDiagonal(img, x, y);
                g = averageCross(img, x, y);
                b = img.getPixel(x, y);
            }
            default -> throw new IllegalStateException("Unexpected pixel color: " + color);
        }
        return packRgb(r, g, b, whiteLevel);
    }

    private static int packRgb(int red, int green, int blue, int whiteLevel) {
        return (clamp8bit(red, whiteLevel) << 16) | (clamp8bit(green, whiteLevel) << 8)
                | clamp8bit(blue, whiteLevel);
    }

    /** Identifies the color type at a given Bayer position. */
    private enum PixelColor {
        RED, GREEN_IN_RED_ROW, GREEN_IN_BLUE_ROW, BLUE
    }

    private static PixelColor getPixelColor(Cr3RawImage.BayerPattern pattern,
                                             boolean evenRow, boolean evenCol) {
        return switch (pattern) {
            case RGGB -> {
                if (evenRow && evenCol) yield PixelColor.RED;
                if (evenRow) yield PixelColor.GREEN_IN_RED_ROW;
                if (evenCol) yield PixelColor.GREEN_IN_BLUE_ROW;
                yield PixelColor.BLUE;
            }
            case BGGR -> {
                if (evenRow && evenCol) yield PixelColor.BLUE;
                if (evenRow) yield PixelColor.GREEN_IN_BLUE_ROW;
                if (evenCol) yield PixelColor.GREEN_IN_RED_ROW;
                yield PixelColor.RED;
            }
            case GRBG -> {
                if (evenRow && evenCol) yield PixelColor.GREEN_IN_RED_ROW;
                if (evenRow) yield PixelColor.RED;
                if (evenCol) yield PixelColor.BLUE;
                yield PixelColor.GREEN_IN_BLUE_ROW;
            }
            case GBRG -> {
                if (evenRow && evenCol) yield PixelColor.GREEN_IN_BLUE_ROW;
                if (evenRow) yield PixelColor.BLUE;
                if (evenCol) yield PixelColor.RED;
                yield PixelColor.GREEN_IN_RED_ROW;
            }
        };
    }

    /** Averages the 4 cross (horizontal + vertical) neighbors. */
    private static int averageCross(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (x > 0) { sum += img.getPixel(x - 1, y); count++; }
        if (x < img.width() - 1) { sum += img.getPixel(x + 1, y); count++; }
        if (y > 0) { sum += img.getPixel(x, y - 1); count++; }
        if (y < img.height() - 1) { sum += img.getPixel(x, y + 1); count++; }
        return count > 0 ? sum / count : 0;
    }

    private static int averageHorizontal(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (x > 0) { sum += img.getPixel(x - 1, y); count++; }
        if (x < img.width() - 1) { sum += img.getPixel(x + 1, y); count++; }
        return count > 0 ? sum / count : 0;
    }

    private static int averageVertical(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (y > 0) { sum += img.getPixel(x, y - 1); count++; }
        if (y < img.height() - 1) { sum += img.getPixel(x, y + 1); count++; }
        return count > 0 ? sum / count : 0;
    }

    private static int averageDiagonal(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (x > 0 && y > 0) { sum += img.getPixel(x - 1, y - 1); count++; }
        if (x < img.width() - 1 && y > 0) { sum += img.getPixel(x + 1, y - 1); count++; }
        if (x > 0 && y < img.height() - 1) { sum += img.getPixel(x - 1, y + 1); count++; }
        if (x < img.width() - 1 && y < img.height() - 1) { sum += img.getPixel(x + 1, y + 1); count++; }
        return count > 0 ? sum / count : 0;
    }

    private static int clamp8bit(int value, int maxVal) {
        return Math.min(255, Math.max(0, (int) ((long) value * 255 / maxVal)));
    }
}
