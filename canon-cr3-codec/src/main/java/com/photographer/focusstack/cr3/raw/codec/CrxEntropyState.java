package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/**
 * Adaptive Rice remainder width and run index for one encoded CRX subband.
 * Both states carry across row boundaries. Copying a run can change the run index
 * while leaving the Rice width unchanged.
 */
final class CrxEntropyState {
    static final int ESCAPE_QUOTIENT = 41;
    static final int LITERAL_BITS = 21;
    static final int RICE_WIDTH_BITS = 4;
    static final int MAX_RICE_WIDTH = (1 << RICE_WIDTH_BITS) - 1;
    private static final int[] RUN_REMAINDER_BITS = {
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
            4, 4, 5, 5, 6, 6, 7, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    private final BitReader bits;
    private int k;
    private int runIndex;

    CrxEntropyState(BitReader bits, int initialK) {
        this.bits = bits;
        this.k = initialK;
    }

    int k() {
        return k;
    }

    void setK(int k) {
        this.k = k;
    }

    // Prediction planes commonly encode a complete symbol in the bit reservoir.
    int readPredictionResidual() throws IOException {
        return bits.readRice(k, ESCAPE_QUOTIENT, LITERAL_BITS);
    }

    /**
     * Extends the first copy signaled by the already consumed positive-run flag.
     * The result is bounded by the number of sites remaining in the row.
     */
    int readRunLength(int remaining) throws IOException {
        long copies = 1;
        while (copies < remaining) {
            int remainderBits = RUN_REMAINDER_BITS[runIndex];
            if (bits.readBit() == 0) {
                copies += bits.readBits(remainderBits);
                runIndex = Math.max(0, runIndex - 1);
                break;
            }
            copies += 1 << remainderBits;
            if (copies <= remaining && runIndex + 1 < RUN_REMAINDER_BITS.length) {
                runIndex++;
            }
        }
        return (int) Math.min(copies, remaining);
    }

    /** Packs a detail residual with the updated Rice width in the low four bits. */
    static int packDetailCode(int k, int value, int quotient) {
        // As in adaptK, decrease below half the scale and increase at the third and sixth multiples.
        int decrease = (2 * value - (1 << k)) >> 31;
        int increase = ((2 - quotient) >>> 31) + ((5 - quotient) >>> 31);
        int width = Math.clamp(k + decrease + increase, 0, MAX_RICE_WIDTH);
        return (value << RICE_WIDTH_BITS) | width;
    }

    void updateK(int value) {
        k = adaptK(k, value);
    }

    static int adaptK(int k, int value) {
        int scale = 1 << k;
        if (2 * value < scale) {
            return Math.max(0, k - 1);
        }
        if (value >= 6 * scale) {
            return Math.min(MAX_RICE_WIDTH, k + 2);
        }
        if (value >= 3 * scale) {
            return Math.min(MAX_RICE_WIDTH, k + 1);
        }
        return k;
    }
}
