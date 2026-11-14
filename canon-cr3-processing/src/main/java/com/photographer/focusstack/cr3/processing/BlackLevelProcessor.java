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
 * <p>Supports global, per-channel and per-row black level correction
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
     * A per-row model can additionally vary the correction between row pairs.</p>
     *
     * @param image      the RAW image to process (modified in place)
     * @param blackLevel the black level model
     */
    public static void process(Cr3RawImage image, BlackLevel blackLevel) {
        process(image, blackLevel, 0, 0);
    }

    /** Subtracts sensor-coordinate levels from an image cropped at (sensorX, sensorY). */
    public static void process(Cr3RawImage image, BlackLevel blackLevel, int sensorX, int sensorY) {
        LOG.debug("Subtracting black level {} from {}x{} image",
                blackLevel, image.width(), image.height());

        int[] data = image.data();

        if (blackLevel.isPerChannel() || blackLevel.isPerRow()) {
            int w = image.width();
            for (int y = 0; y < image.height(); y++) {
                int row = y * w;
                // A Bayer row alternates between exactly two black levels.
                for (int phase = 0; phase < 2; phase++) {
                    int bl = blackLevel.levelAt(sensorX + phase, sensorY + y, image.bayerPattern());
                    for (int x = phase; x < w; x += 2) {
                        data[row + x] = Math.max(0, data[row + x] - bl);
                    }
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
