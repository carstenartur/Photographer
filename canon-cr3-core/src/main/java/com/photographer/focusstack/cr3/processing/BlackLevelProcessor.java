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
 * <p>For the Canon PowerShot G5 X Mark II (20 MP, 1" sensor), typical black
 * levels are around 512 for 14-bit data.</p>
 *
 * TODO: Auto-detect black level from OB regions in the image
 * TODO: Support per-channel black levels
 * TODO: Support per-row black level correction
 */
public final class BlackLevelProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(BlackLevelProcessor.class);

    /** Default black level for Canon 14-bit sensors. */
    public static final int DEFAULT_BLACK_LEVEL = 512;

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
        LOG.debug("Subtracting black level {} from {}x{} image",
                blackLevel, image.width(), image.height());

        int[] data = image.data();
        for (int i = 0; i < data.length; i++) {
            data[i] = Math.max(0, data[i] - blackLevel);
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
