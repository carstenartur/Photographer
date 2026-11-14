package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/** Adaptive Rice parameter and run-length state, local to one encoded CRX subband. */
final class CrxEntropyState {
    private static final int ESCAPE_QUOTIENT = 41;
    private static final int LITERAL_BITS = 21;
    private static final int MAX_RICE_WIDTH = 15;
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

    int readResidual() throws IOException {
        int quotient = bits.readUnary(ESCAPE_QUOTIENT);
        return quotient == ESCAPE_QUOTIENT ? bits.readBits(LITERAL_BITS) : (quotient << k) + bits.readBits(k);
    }

    // Prediction planes commonly encode a complete symbol in the bit reservoir.
    // Detail bands favor short codes and use the separate prefix/remainder reader.
    int readPredictionResidual() throws IOException {
        return bits.readRice(k, ESCAPE_QUOTIENT, LITERAL_BITS);
    }

    /** The caller has already consumed the positive-run flag, which accounts for one copy. */
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

    void updateK(int value) {
        int scale = 1 << k;
        if (2 * value < scale) {
            k = Math.max(0, k - 1);
        } else if (value >= 6 * scale) {
            k = Math.min(MAX_RICE_WIDTH, k + 2);
        } else if (value >= 3 * scale) {
            k = Math.min(MAX_RICE_WIDTH, k + 1);
        }
    }
}
