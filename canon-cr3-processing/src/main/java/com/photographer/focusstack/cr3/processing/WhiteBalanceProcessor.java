package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies white balance multipliers to RAW sensor data.
 *
 * <p>White balance corrects for the color temperature of the light source
 * by scaling the R, G, and B channels independently. The multipliers are
 * extracted from the Canon MakerNote (CMT3) metadata when available, or
 * default daylight values are used as fallback.</p>
 */
public final class WhiteBalanceProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(WhiteBalanceProcessor.class);

    /** Default daylight white balance multipliers for Canon (approximate). */
    public static final double[] DEFAULT_DAYLIGHT_MULTIPLIERS = {1.9, 1.0, 1.5};

    private WhiteBalanceProcessor() {
        // utility class
    }

    /**
     * Applies white balance multipliers to the RAW image.
     *
     * <p>Multipliers are applied per Bayer channel: R, G, B. The RGGB pattern
     * determines which multiplier applies to each pixel position.</p>
     *
     * @param image       the RAW image to process (modified in place)
     * @param multipliers array of 3 multipliers [R, G, B]
     */
    public static void process(Cr3RawImage image, double[] multipliers) {
        if (multipliers.length != 3) {
            throw new IllegalArgumentException("Expected 3 multipliers [R, G, B], got " + multipliers.length);
        }

        LOG.debug("Applying white balance: R={}, G={}, B={}",
                multipliers[0], multipliers[1], multipliers[2]);

        int w = image.width();
        int h = image.height();
        int maxVal = image.maxValue();
        int[] data = image.data();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                double mult = getMultiplier(x, y, image.bayerPattern(), multipliers);
                data[idx] = Math.min(maxVal, Math.max(0, (int) (data[idx] * mult)));
            }
        }
    }

    /**
     * Applies default daylight white balance.
     *
     * @param image the RAW image to process (modified in place)
     */
    public static void process(Cr3RawImage image) {
        process(image, DEFAULT_DAYLIGHT_MULTIPLIERS);
    }

    private static double getMultiplier(int x, int y, Cr3RawImage.BayerPattern pattern,
                                         double[] multipliers) {
        boolean evenRow = (y % 2) == 0;
        boolean evenCol = (x % 2) == 0;

        return switch (pattern) {
            case RGGB -> {
                if (evenRow && evenCol) yield multipliers[0];       // R
                else if (!evenRow && !evenCol) yield multipliers[2]; // B
                else yield multipliers[1];                           // G
            }
            case BGGR -> {
                if (evenRow && evenCol) yield multipliers[2];       // B
                else if (!evenRow && !evenCol) yield multipliers[0]; // R
                else yield multipliers[1];                           // G
            }
            case GRBG -> {
                if (evenRow && !evenCol) yield multipliers[0];      // R
                else if (!evenRow && evenCol) yield multipliers[2];  // B
                else yield multipliers[1];                           // G
            }
            case GBRG -> {
                if (evenRow && !evenCol) yield multipliers[2];      // B
                else if (!evenRow && evenCol) yield multipliers[0];  // R
                else yield multipliers[1];                           // G
            }
        };
    }
}
