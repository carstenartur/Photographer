package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * Demosaics a Bayer-pattern RAW image into a full-color RGB image.
 *
 * <p>Uses bilinear interpolation as the initial implementation. More advanced
 * algorithms (AHD, DCB, AMaZE) can be added later.</p>
 *
 * <p>The Canon PowerShot G5 X Mark II uses an RGGB Bayer pattern.</p>
 *
 * TODO: Implement AHD (Adaptive Homogeneity-Directed) demosaicing
 * TODO: Implement edge-aware interpolation
 * TODO: Handle non-RGGB patterns
 */
public final class BayerDemosaicer {

    private static final Logger LOG = LoggerFactory.getLogger(BayerDemosaicer.class);

    private BayerDemosaicer() {
        // utility class
    }

    /**
     * Demosaics a Bayer-pattern RAW image using bilinear interpolation.
     *
     * @param rawImage the RAW Bayer image
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB for 8-bit,
     *         TYPE_USHORT_565 or custom for 16-bit)
     */
    public static BufferedImage demosaic(Cr3RawImage rawImage) {
        LOG.info("Demosaicing {}x{} {} image with bilinear interpolation",
                rawImage.width(), rawImage.height(), rawImage.bayerPattern());

        int w = rawImage.width();
        int h = rawImage.height();
        int maxVal = rawImage.maxValue();

        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        // Bilinear interpolation for RGGB pattern
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r, g, b;

                boolean evenRow = (y % 2) == 0;
                boolean evenCol = (x % 2) == 0;

                if (evenRow && evenCol) {
                    // Red pixel
                    r = rawImage.getPixel(x, y);
                    g = averageGreenAtRed(rawImage, x, y);
                    b = averageDiagonal(rawImage, x, y);
                } else if (evenRow) {
                    // Green pixel in red row
                    r = averageHorizontal(rawImage, x, y);
                    g = rawImage.getPixel(x, y);
                    b = averageVertical(rawImage, x, y);
                } else if (evenCol) {
                    // Green pixel in blue row
                    r = averageVertical(rawImage, x, y);
                    g = rawImage.getPixel(x, y);
                    b = averageHorizontal(rawImage, x, y);
                } else {
                    // Blue pixel
                    r = averageDiagonal(rawImage, x, y);
                    g = averageGreenAtBlue(rawImage, x, y);
                    b = rawImage.getPixel(x, y);
                }

                // Scale to 8-bit
                r = clamp8bit(r, maxVal);
                g = clamp8bit(g, maxVal);
                b = clamp8bit(b, maxVal);

                output.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        return output;
    }

    private static int averageGreenAtRed(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (x > 0) { sum += img.getPixel(x - 1, y); count++; }
        if (x < img.width() - 1) { sum += img.getPixel(x + 1, y); count++; }
        if (y > 0) { sum += img.getPixel(x, y - 1); count++; }
        if (y < img.height() - 1) { sum += img.getPixel(x, y + 1); count++; }
        return count > 0 ? sum / count : 0;
    }

    private static int averageGreenAtBlue(Cr3RawImage img, int x, int y) {
        return averageGreenAtRed(img, x, y);
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
