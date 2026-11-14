package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/** Entropy and prediction for a 14-bit, single-subband lossless CRX plane. */
final class CrxNativeLosslessPlane {
    private static final int HALF_RANGE = 8192;
    private static final int ESCAPE_QUOTIENT = 41;
    private static final int[] RUN_REMAINDER_BITS = {
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
            4, 4, 5, 5, 6, 6, 7, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    private final BitReader bits;
    private int k = 2;
    private int runIndex;

    private CrxNativeLosslessPlane(BitReader bits) {
        this.bits = bits;
    }

    /**
     * Reads only the declared subband slice. Plane neighbours are two columns and two
     * sensor rows apart in the destination mosaic. All predictor state belongs to this plane.
     */
    static void decode(byte[] compressed, int start, int length, int width, int height,
                       int[] mosaic, int stride, int firstPixel) throws IOException {
        if (start < 0 || length < 0 || start > compressed.length - length) {
            throw new IllegalArgumentException("Invalid compressed plane range");
        }
        if (width <= 0 || height <= 0 || stride <= 0 || firstPixel < 0
                || firstPixel % stride + 2L * (width - 1) >= stride
                || firstPixel + 2L * (width - 1) + 2L * (height - 1) * stride >= mosaic.length) {
            throw new IllegalArgumentException("Plane exceeds destination mosaic geometry");
        }
        var plane = new CrxNativeLosslessPlane(new BitReader(compressed, start, length));
        plane.readFirstRow(mosaic, firstPixel, width);
        int row = firstPixel;
        for (int y = 1; y < height; y++) {
            int previous = row;
            row += 2 * stride;
            plane.readRow(mosaic, previous, row, width);
        }
    }

    private void readFirstRow(int[] mosaic, int first, int width) throws IOException {
        // Origins here count from the CMP1-declared header end. RG3 measured the
        // same 14 physical bits at offsets 18–31 from its four-byte-shifted origin.
        if (bits.readBits(32) != 0 || bits.readBits(18) != 128) {
            throw new IOException("Unsupported opening of a 14-bit lossless CRX plane");
        }
        int value = reconstruct(HALF_RANGE, bits.readBits(14));
        mosaic[first] = value;
        for (int x = 1; x < width; x++) {
            int unsigned = readResidual();
            value = reconstruct(value, unsigned);
            mosaic[first + 2 * x] = value;
            updateK(unsigned);
        }
    }

    private void readRow(int[] mosaic, int previous, int row, int width) throws IOException {
        int x = 0;
        while (x < width) {
            int above = mosaic[previous + 2 * x];
            int left = x == 0 ? above : mosaic[row + 2 * (x - 1)];
            int aboveLeft = x == 0 ? above : mosaic[previous + 2 * (x - 1)];
            int aboveRight = x + 1 == width ? above : mosaic[previous + 2 * (x + 1)];
            int prediction = CrxMedPredictor.med(left, above, aboveLeft);

            if (x + 1 < width && left == above && left == aboveRight && bits.readBit() == 1) {
                int end = x + readRunLength(width - x);
                while (x < end) {
                    mosaic[row + 2 * x] = left;
                    x++;
                }
                if (x == width) {
                    return;
                }
                above = mosaic[previous + 2 * x];
                aboveRight = x + 1 == width ? above : mosaic[previous + 2 * (x + 1)];
                prediction = above;
            }

            int unsigned = readResidual();
            mosaic[row + 2 * x] = reconstruct(prediction, unsigned);
            int adaptationValue = x + 1 == width ? unsigned : (unsigned >>> 1) + Math.abs(aboveRight - above);
            updateK(adaptationValue);
            x++;
        }
    }

    private int readResidual() throws IOException {
        int quotient = 0;
        while (bits.readBit() == 0) {
            if (++quotient > ESCAPE_QUOTIENT) {
                throw new IOException("Unsupported CRX lossless Rice quotient");
            }
        }
        return quotient == ESCAPE_QUOTIENT ? bits.readBits(21) : (quotient << k) + bits.readBits(k);
    }

    /** The caller has already consumed the positive-run flag, which accounts for one copy. */
    private int readRunLength(int remaining) throws IOException {
        int copies = 1;
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
        return Math.min(copies, remaining);
    }

    private void updateK(int value) {
        int scale = 1 << k;
        if (2 * value < scale) {
            k = Math.max(0, k - 1);
        } else if (value >= 6 * scale) {
            k += 2;
        } else if (value >= 3 * scale) {
            k++;
        }
    }

    private static int reconstruct(int prediction, int unsigned) throws IOException {
        int value = prediction + CrxResidualSignMapper.decode(unsigned, CrxResidualSignMapper.Mapping.ZIG_ZAG);
        if (value < 0 || value >= 2 * HALF_RANGE) {
            throw new IOException("CRX sample outside the 14-bit sensor range: " + value);
        }
        return value;
    }
}
