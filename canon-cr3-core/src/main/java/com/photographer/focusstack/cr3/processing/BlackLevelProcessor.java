package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subtracts optical black level from RAW sensor data.
 *
 * <p>Canon sensors have optical black (OB) regions around the edges of the sensor.
 * The black level represents the sensor's dark current noise floor and must be
 * subtracted before any further processing.</p>
 *
 * <p>Supports global, per-channel, and (future) per-row black level correction
 * via the {@link BlackLevel} model.</p>
 */
public final class BlackLevelProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(BlackLevelProcessor.class);

    /** Default black level for Canon 14-bit sensors. */
    public static final int DEFAULT_BLACK_LEVEL = BlackLevel.DEFAULT_14BIT;

    private BlackLevelProcessor() {
        // utility class
    }

    /**
     * Subtracts the black level from all pixels in the RAW image.
     *
     * @param image      the RAW image to process (modified in place)
     * @param blackLevel the black level to subtract
     */
    public static void process(Cr3RawImage image, int blackLevel) {
        process(image, BlackLevel.global(blackLevel));
    }

    /**
     * Subtracts black levels from a RAW image using a {@link BlackLevel} model.
     *
     * <p>If the model is per-channel, each Bayer position gets its own correction.
     * Otherwise, a uniform subtraction is applied.</p>
     *
     * @param image      the RAW image to process (modified in place)
     * @param blackLevel the black level model
     */
    public static void process(Cr3RawImage image, BlackLevel blackLevel) {
        LOG.debug("Subtracting black level {} from {}x{} image",
                blackLevel, image.width(), image.height());

        int[] data = image.data();

        if (blackLevel.isPerChannel()) {
            int w = image.width();
            int h = image.height();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    int bl = blackLevel.levelAt(x, y, image.bayerPattern());
                    data[idx] = Math.max(0, data[idx] - bl);
                }
            }
        } else {
            int bl = blackLevel.globalLevel();
            for (int i = 0; i < data.length; i++) {
                data[i] = Math.max(0, data[i] - bl);
            }
        }
    }

    /**
     * Subtracts the default black level.
     *
     * @param image the RAW image to process (modified in place)
     */
    public static void process(Cr3RawImage image) {
        process(image, DEFAULT_BLACK_LEVEL);
    }
}
