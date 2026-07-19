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
 *   <li>For each row pair (Bayer: even/odd), compute the median or mean of OB pixels</li>
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

    private ObBlackLevelEstimator() {}

    /**
     * Estimates per-row black levels from the left-side OB region.
     *
     * @param image           the raw Bayer image (including OB border)
     * @param obColumnsRight  the right boundary of the OB region (typically activeAreaLeft)
     * @return a per-row {@link BlackLevel}, or {@code null} if OB estimation is not feasible
     */
    public static BlackLevel estimate(Cr3RawImage image, int obColumnsRight) {
        if (obColumnsRight < MIN_OB_COLUMNS) {
            LOG.debug("OB region too narrow ({} columns < minimum {}), skipping per-row estimation",
                    obColumnsRight, MIN_OB_COLUMNS);
            return null;
        }

        // Clamp to actual image width
        int obRight = Math.min(obColumnsRight, image.width());
        if (obRight < MIN_OB_COLUMNS) {
            return null;
        }

        int w = image.width();
        int h = image.height();
        int halfH = h / 2;
        int[] data = image.data();

        // For a Bayer image, rows alternate: even rows contain R/Gr, odd rows contain Gb/B.
        // We compute mean OB value per Bayer half-row for each of the 4 channels.
        // channel 0 = even row, even col (e.g. R in RGGB)
        // channel 1 = even row, odd col  (e.g. Gr)
        // channel 2 = odd row, even col   (e.g. Gb)
        // channel 3 = odd row, odd col    (e.g. B)
        int[][] perRowLevels = new int[4][halfH];

        for (int y = 0; y < h; y++) {
            int halfRow = y / 2;
            if (halfRow >= halfH) break;

            boolean evenRow = (y % 2) == 0;

            // Accumulate even-col and odd-col OB pixels separately
            long sumEvenCol = 0;
            int countEvenCol = 0;
            long sumOddCol = 0;
            int countOddCol = 0;

            for (int x = 0; x < obRight; x++) {
                int val = data[y * w + x];
                if ((x % 2) == 0) {
                    sumEvenCol += val;
                    countEvenCol++;
                } else {
                    sumOddCol += val;
                    countOddCol++;
                }
            }

            // Map to channels based on row parity
            if (evenRow) {
                // channel 0 = even row, even col
                perRowLevels[0][halfRow] = countEvenCol > 0 ? (int) (sumEvenCol / countEvenCol) : 0;
                // channel 1 = even row, odd col
                perRowLevels[1][halfRow] = countOddCol > 0 ? (int) (sumOddCol / countOddCol) : 0;
            } else {
                // channel 2 = odd row, even col
                perRowLevels[2][halfRow] = countEvenCol > 0 ? (int) (sumEvenCol / countEvenCol) : 0;
                // channel 3 = odd row, odd col
                perRowLevels[3][halfRow] = countOddCol > 0 ? (int) (sumOddCol / countOddCol) : 0;
            }
        }

        LOG.debug("Estimated per-row black level from {} OB columns, {} half-rows",
                obRight, halfH);
        return BlackLevel.perRow(perRowLevels);
    }
}
