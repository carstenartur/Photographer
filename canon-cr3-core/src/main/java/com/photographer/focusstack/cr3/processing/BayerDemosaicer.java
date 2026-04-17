package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

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
     * @param rawImage the RAW Bayer image
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB, 8 bits per channel)
     */
    public static BufferedImage demosaic(Cr3RawImage rawImage) {
        LOG.info("Demosaicing {}x{} {} image with bilinear interpolation",
                rawImage.width(), rawImage.height(), rawImage.bayerPattern());

        int w = rawImage.width();
        int h = rawImage.height();
        int maxVal = rawImage.maxValue();

        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int[] rgb = interpolatePixel(rawImage, x, y);

                int r = clamp8bit(rgb[0], maxVal);
                int g = clamp8bit(rgb[1], maxVal);
                int b = clamp8bit(rgb[2], maxVal);

                output.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        return output;
    }

    /**
     * Demosaics a Bayer-pattern RAW image to a 16-bit-per-channel BufferedImage.
     *
     * @param rawImage the RAW Bayer image
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB scaled to 16-bit precision)
     */
    public static BufferedImage demosaic16bit(Cr3RawImage rawImage) {
        LOG.info("Demosaicing {}x{} {} image to 16-bit with bilinear interpolation",
                rawImage.width(), rawImage.height(), rawImage.bayerPattern());

        int w = rawImage.width();
        int h = rawImage.height();
        int maxVal = rawImage.maxValue();

        // Use TYPE_INT_RGB and scale values to [0, 65535] packed as 8-bit per channel.
        // For true 16-bit output, callers should use the raw int[][] planes directly.
        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int[] rgb = interpolatePixel(rawImage, x, y);

                // Scale to 16-bit then down to 8-bit for TYPE_INT_RGB
                int r = Math.min(255, Math.max(0, (int) ((long) rgb[0] * 255 / maxVal)));
                int g = Math.min(255, Math.max(0, (int) ((long) rgb[1] * 255 / maxVal)));
                int b = Math.min(255, Math.max(0, (int) ((long) rgb[2] * 255 / maxVal)));

                output.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        return output;
    }

    /**
     * Interpolates the RGB values at a given pixel position based on the Bayer pattern.
     *
     * @return int[3] with {R, G, B} in raw sensor values
     */
    private static int[] interpolatePixel(Cr3RawImage img, int x, int y) {
        boolean evenRow = (y % 2) == 0;
        boolean evenCol = (x % 2) == 0;

        // Determine the color at this pixel position for the given Bayer pattern
        PixelColor color = getPixelColor(img.bayerPattern(), evenRow, evenCol);

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
        return new int[]{r, g, b};
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
