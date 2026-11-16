package com.photographer.focusstack.cr3.raw.codec;

import java.util.Objects;

/** CRX lossless MED prediction over an already reconstructed plane. */
final class CrxMedPredictor {

    private static final int FIRST_PIXEL_SEED = 0;

    private CrxMedPredictor() {
    }

    static int predict(int[] reconstructed, int width, int x, int y) {
        Objects.requireNonNull(reconstructed, "reconstructed");
        if (width <= 0 || x < 0 || y < 0 || x >= width) {
            throw new IllegalArgumentException("Invalid predictor coordinates: width=" + width
                    + ", x=" + x + ", y=" + y);
        }
        int index = Math.addExact(Math.multiplyExact(y, width), x);
        if (index >= reconstructed.length) {
            throw new IllegalArgumentException("Predictor coordinates exceed reconstructed plane");
        }

        if (x == 0 && y == 0) {
            return FIRST_PIXEL_SEED;
        }
        if (y == 0) {
            return reconstructed[index - 1];
        }
        if (x == 0) {
            return reconstructed[index - width];
        }
        return med(reconstructed[index - 1], reconstructed[index - width], reconstructed[index - width - 1]);
    }

    static int med(int left, int above, int aboveLeft) {
        int lo = Math.min(left, above);
        int hi = Math.max(left, above);
        // Subtracting the clamped upper-left sample keeps the prediction in [lo, hi].
        return left + above - Math.min(hi, Math.max(lo, aboveLeft));
    }
}
