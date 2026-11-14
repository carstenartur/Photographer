package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/** Entropy and prediction for a 14-bit, single-subband lossless CRX plane. */
final class CrxNativeLosslessPlane {
    private static final int HALF_RANGE = 8192;
    private final BitReader bits;
    private final CrxEntropyState state;
    private final boolean sensorValues;

    private CrxNativeLosslessPlane(BitReader bits, boolean sensorValues) {
        this.bits = bits;
        this.state = new CrxEntropyState(bits, 2);
        this.sensorValues = sensorValues;
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
        var plane = new CrxNativeLosslessPlane(new BitReader(compressed, start, length), true);
        plane.decodeRows(mosaic, firstPixel, width, height, 2 * stride, 2);
    }

    /** Reads a C-RAW approximation band in raster order, preserving signed coefficients. */
    static int[] decodeCoefficients(byte[] compressed, int start, int length, int width, int height)
            throws IOException {
        if (start < 0 || length < 0 || start > compressed.length - length) {
            throw new IllegalArgumentException("Invalid compressed approximation band range");
        }
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid approximation band geometry");
        }
        var bits = new BitReader(compressed, start, length);
        var plane = new CrxNativeLosslessPlane(bits, false);
        int[] samples = new int[width * height];
        plane.decodeRows(samples, 0, width, height, width, 1);
        if (bits.consumedBytes() != length) {
            throw new IOException("CRX approximation band byte count differs from its declared entropy length");
        }
        return samples;
    }

    private void decodeRows(int[] output, int first, int width, int height, int rowStep, int columnStep)
            throws IOException {
        readFirstRow(output, first, width, columnStep);
        int row = first;
        for (int y = 1; y < height; y++) {
            int previous = row;
            row += rowStep;
            readRow(output, previous, row, width, columnStep);
        }
    }

    private void readFirstRow(int[] mosaic, int first, int width, int columnStep) throws IOException {
        // The physical opening consumes 64 bits in both layouts. Approximation
        // bands can use the full 21-bit residual, including values outside the
        // sensor range. The verified lossless sensor profile uses 14 bits.
        int literalBits = sensorValues ? 14 : 21;
        int prefix = sensorValues ? 128 : 1;
        if (bits.readBits(32) != 0 || bits.readBits(32 - literalBits) != prefix) {
            throw new IOException("Unsupported opening of a 14-bit CRX plane");
        }
        int value = reconstruct(HALF_RANGE, bits.readBits(literalBits));
        mosaic[first] = value;
        for (int x = 1; x < width; x++) {
            int unsigned = state.readResidual();
            value = reconstruct(value, unsigned);
            mosaic[first + columnStep * x] = value;
            state.updateK(unsigned);
        }
    }

    private void readRow(int[] mosaic, int previous, int row, int width, int columnStep) throws IOException {
        int x = 0;
        while (x < width) {
            int above = mosaic[previous + columnStep * x];
            int left = x == 0 ? above : mosaic[row + columnStep * (x - 1)];
            int aboveLeft = x == 0 ? above : mosaic[previous + columnStep * (x - 1)];
            int aboveRight = x + 1 == width ? above : mosaic[previous + columnStep * (x + 1)];
            int prediction = CrxMedPredictor.med(left, above, aboveLeft);

            if (x + 1 < width && left == above && left == aboveRight && bits.readBit() == 1) {
                int end = x + state.readRunLength(width - x);
                while (x < end) {
                    mosaic[row + columnStep * x] = left;
                    x++;
                }
                if (x == width) {
                    return;
                }
                above = mosaic[previous + columnStep * x];
                aboveRight = x + 1 == width ? above : mosaic[previous + columnStep * (x + 1)];
                prediction = above;
            }

            int unsigned = state.readResidual();
            mosaic[row + columnStep * x] = reconstruct(prediction, unsigned);
            int adaptationValue = x + 1 == width ? unsigned : (unsigned >>> 1) + Math.abs(aboveRight - above);
            state.updateK(adaptationValue);
            x++;
        }
    }

    private int reconstruct(int prediction, int unsigned) throws IOException {
        int value = prediction + CrxResidualSignMapper.decode(unsigned, CrxResidualSignMapper.Mapping.ZIG_ZAG);
        if (!sensorValues && (value < -(1 << 20) || value >= 1 << 20)) {
            throw new IOException("CRX approximation coefficient outside the signed 21-bit range: " + value);
        }
        if (sensorValues && (value < 0 || value >= 2 * HALF_RANGE)) {
            throw new IOException("CRX sample outside the 14-bit sensor range: " + value);
        }
        return value;
    }
}
