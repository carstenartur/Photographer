package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.Cr3Workers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies white balance multipliers to RAW sensor data.
 *
 * <p>White balance corrects for the color temperature of the light source
 * by scaling the R, G, and B channels independently. The multipliers are
 * obtained from the capture metadata when available, with approximate
 * daylight values as fallback.</p>
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
        process(image, multipliers, null, 0, 0, 1);
    }

    /**
     * Applies optional black-level correction and white balance in one pass.
     * The pedestal uses sensor coordinates and white balance uses the image's local Bayer phase.
     *
     * @param image the image to process
     * @param multipliers array of 3 multipliers [R, G, B]
     * @param blackLevel the sensor pedestal, or null to apply white balance alone
     * @param sensorX sensor column corresponding to the first image column
     * @param sensorY sensor row corresponding to the first image row
     * @param maxWorkers maximum number of workers, or zero for automatic selection
     */
    public static void process(Cr3RawImage image, double[] multipliers, BlackLevel blackLevel,
                               int sensorX, int sensorY, int maxWorkers) {
        if (multipliers.length != 3) {
            throw new IllegalArgumentException("Expected 3 multipliers [R, G, B], got " + multipliers.length);
        }

        LOG.debug("Applying white balance: R={}, G={}, B={}",
                multipliers[0], multipliers[1], multipliers[2]);

        int width = image.width();
        int height = image.height();
        int stripes = Cr3Workers.count(maxWorkers, Pixels.maxStripes(width, height));
        if (width <= 0 || height <= 0) {
            return;
        }
        int maxVal = image.maxValue();
        int[] data = image.data();
        var pattern = image.bayerPattern();
        Cr3Workers.run(stripes, stripe -> {
            int end = Pixels.stripeStart(stripe + 1, height, stripes);
            for (int y = Pixels.stripeStart(stripe, height, stripes); y < end; y++) {
                double even = getMultiplier(0, y, pattern, multipliers);
                double odd = getMultiplier(1, y, pattern, multipliers);
                int first = y * width;
                if (blackLevel == null) {
                    balanceRow(data, first, first + width, even, odd, maxVal);
                } else {
                    int evenBlack = blackLevel.levelAt(sensorX, sensorY + y, pattern);
                    int oddBlack = blackLevel.levelAt(sensorX + 1, sensorY + y, pattern);
                    correctRow(data, first, first + width, evenBlack, oddBlack, even, odd, maxVal);
                }
            }
        });
    }

    private static void balanceRow(int[] data, int first, int end, double even, double odd, int maxVal) {
        int i = first;
        for (; i + 1 < end; i += 2) {
            data[i] = multiply(data[i], even, maxVal);
            data[i + 1] = multiply(data[i + 1], odd, maxVal);
        }
        if (i < end) {
            data[i] = multiply(data[i], even, maxVal);
        }
    }

    private static void correctRow(int[] data, int first, int end, int evenBlack, int oddBlack,
                                   double even, double odd, int maxVal) {
        int i = first;
        for (; i + 1 < end; i += 2) {
            data[i] = multiply(Math.max(0, data[i] - evenBlack), even, maxVal);
            data[i + 1] = multiply(Math.max(0, data[i + 1] - oddBlack), odd, maxVal);
        }
        if (i < end) {
            data[i] = multiply(Math.max(0, data[i] - evenBlack), even, maxVal);
        }
    }

    private static int multiply(int value, double multiplier, int maxVal) {
        return Math.min(maxVal, Math.max(0, (int) (value * multiplier)));
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
