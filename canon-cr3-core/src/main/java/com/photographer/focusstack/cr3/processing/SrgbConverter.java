package com.photographer.focusstack.cr3.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * Converts linear RGB data to sRGB with gamma correction.
 *
 * <p>The sRGB transfer function applies a gamma curve to linear light values,
 * making the image suitable for display on standard monitors.</p>
 *
 * <p>The standard sRGB gamma function is:</p>
 * <ul>
 *   <li>For linear values ≤ 0.0031308: sRGB = 12.92 × linear</li>
 *   <li>For linear values > 0.0031308: sRGB = 1.055 × linear^(1/2.4) - 0.055</li>
 * </ul>
 */
public final class SrgbConverter {

    private static final Logger LOG = LoggerFactory.getLogger(SrgbConverter.class);

    /** Pre-computed sRGB gamma lookup table (256 entries, linear 8-bit → sRGB 8-bit). */
    private static final int[] GAMMA_LUT = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            double linear = i / 255.0;
            double srgb;
            if (linear <= 0.0031308) {
                srgb = 12.92 * linear;
            } else {
                srgb = 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
            }
            GAMMA_LUT[i] = Math.min(255, Math.max(0, (int) (srgb * 255 + 0.5)));
        }
    }

    private SrgbConverter() {
        // utility class
    }

    /**
     * Applies sRGB gamma correction to a linear RGB image.
     *
     * @param image the linear RGB image (modified in place)
     */
    public static void process(BufferedImage image) {
        LOG.debug("Applying sRGB gamma to {}x{} image", image.getWidth(), image.getHeight());

        int w = image.getWidth();
        int h = image.getHeight();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = GAMMA_LUT[(rgb >> 16) & 0xFF];
                int g = GAMMA_LUT[(rgb >> 8) & 0xFF];
                int b = GAMMA_LUT[rgb & 0xFF];
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
    }

    /**
     * Applies sRGB gamma to a single linear value (0.0 - 1.0).
     *
     * @param linear the linear value
     * @return the sRGB value
     */
    public static double linearToSrgb(double linear) {
        if (linear <= 0.0031308) {
            return 12.92 * linear;
        }
        return 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
    }
}
