package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Estimates per-row black levels from optical black (OB) border regions.
 *
 * <p>Canon sensors typically have masked (light-shielded) columns at the left edge
 * of the sensor. These "optical black" columns have the same electronic noise
 * characteristics as the active pixels but are not exposed to light. By averaging
 * these columns, we can estimate the row-dependent black level.</p>
 *
 * <h2>Approach</h2>
 * <ol>
 *   <li>Identify OB columns from active area metadata (columns left of activeAreaLeft)</li>
 *   <li>For each row pair (Bayer: even/odd), compute the mean of OB pixels</li>
 *   <li>Separate by Bayer channel (R, Gr, Gb, B)</li>
 *   <li>Return a {@link BlackLevel} with per-row correction values</li>
 * </ol>
 *
 * <p>If OB columns are too narrow or absent, the estimator returns {@code null}
 * and the caller should fall back to per-channel or global black level.</p>
 *
 * <p>This is a cleanroom implementation based on general sensor OB correction
 * concepts documented in image processing literature. No proprietary code was used.</p>
 */
public final class ObBlackLevelEstimator {

    private static final Logger LOG = LoggerFactory.getLogger(ObBlackLevelEstimator.class);

    /**
     * Minimum number of OB columns required for reliable estimation.
     * Canon sensors typically have 100-200 OB columns; fewer than 4 would be
     * too noisy for reliable estimation.
     */
    public static final int MIN_OB_COLUMNS = 4;

    private final int obColumns;
    private final long[][] sums;

    private ObBlackLevelEstimator(int obColumns, int height) {
        this.obColumns = obColumns;
        this.sums = new long[4][height / 2];
    }

    /**
     * Starts an estimate for the full sensor. Returns null when the available
     * left border is too narrow. Only the sums for complete Bayer row pairs are retained.
     */
    public static ObBlackLevelEstimator forSensor(int width, int height, int obColumnsRight) {
        int obColumns = Math.min(width, obColumnsRight);
        return obColumns >= MIN_OB_COLUMNS ? new ObBlackLevelEstimator(obColumns, height) : null;
    }

    /** Adds the masked sites in a tile-plane row at sensor positions x, x+2, ... */
    public void addPlaneRow(int x, int y, int[] samples) {
        if (x < obColumns) {
            int count = Math.min(samples.length, Math.ceilDiv(obColumns - x, 2));
            accumulate(x, y, samples, 0, 1, count);
        }
    }

    private void accumulate(int x, int y, int[] data, int offset, int stride, int count) {
        int channel = 2 * (y & 1) + (x & 1);
        int halfRow = y / 2;
        if (halfRow < sums[channel].length) {
            long sum = 0;
            for (int i = 0; i < count; i++, offset += stride) {
                sum += data[offset];
            }
            sums[channel][halfRow] += sum;
        }
    }

    /** Returns the truncated mean per row and Bayer phase after every tile has been visited. */
    public BlackLevel blackLevel() {
        int halfHeight = sums[0].length;
        int[][] levels = new int[4][halfHeight];
        for (int channel = 0; channel < 4; channel++) {
            int count = sampleCount(channel & 1);
            for (int i = 0; i < halfHeight; i++) {
                levels[channel][i] = (int) (sums[channel][i] / count);
            }
        }
        LOG.debug("Estimated per-row black level from {} OB columns, {} half-rows", obColumns, halfHeight);
        return BlackLevel.perRow(levels);
    }

    private int sampleCount(int columnParity) {
        return Math.ceilDiv(obColumns - columnParity, 2);
    }

    /** Estimates the same per-row black levels from an already decoded full-sensor image. */
    public static BlackLevel estimate(Cr3RawImage image, int obColumnsRight) {
        var estimator = forSensor(image.width(), image.height(), obColumnsRight);
        if (estimator == null) {
            return null;
        }
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < 2; x++) {
                estimator.accumulate(x, y, image.data(), y * image.width() + x, 2, estimator.sampleCount(x));
            }
        }
        return estimator.blackLevel();
    }
}
