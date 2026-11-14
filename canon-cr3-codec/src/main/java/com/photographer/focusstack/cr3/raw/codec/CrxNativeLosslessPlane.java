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

    static void decodeSensor(byte[] compressed, int start, int length, int width, int height,
                             int x, int y, CrxSensorDecoder.RowConsumer consumer) throws IOException {
        decodeSensor(new BitReader(compressed, start, length), width, height, x, y, consumer);
    }

    static void decodeSensor(BitReader bits, int width, int height,
                             int x, int y, CrxSensorDecoder.RowConsumer consumer) throws IOException {
        var plane = new CrxNativeLosslessPlane(bits, true);
        int[] current = new int[width];
        int[] previous = new int[width];
        plane.readFirstRow(current, width);
        consumer.write(x, y, current);
        for (int row = 1; row < height; row++) {
            int[] swap = previous;
            previous = current;
            current = swap;
            plane.readRow(previous, current, width);
            consumer.write(x, y + 2 * row, current);
        }
    }

    /** Sequential, signed rows of the coarsest C-RAW approximation band. */
    static CrxWaveletTile.Band approximation(byte[] compressed, int start, int length, int width, int height) {
        return approximation(new BitReader(compressed, start, length), width, height);
    }

    static CrxWaveletTile.Band approximation(BitReader bits, int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid approximation band geometry");
        }
        var plane = new CrxNativeLosslessPlane(bits, false);
        return new CrxWaveletTile.Band(width, height, new CrxWaveletTile.RowReader() {
            private final int[] previous = new int[width];
            private int row;

            @Override
            public void read(int[] output) throws IOException {
                if (row == 0) {
                    plane.readFirstRow(output, width);
                } else {
                    plane.readRow(previous, output, width);
                }
                System.arraycopy(output, 0, previous, 0, width);
                if (++row == height && bits.consumedBytes() != bits.length()) {
                    throw new IOException("CRX approximation band byte count differs from its declared entropy length");
                }
            }
        });
    }

    private void readFirstRow(int[] output, int width) throws IOException {
        // The physical opening consumes 64 bits in both layouts. Approximation
        // bands can use the full 21-bit residual, including values outside the
        // sensor range. The verified lossless sensor profile uses 14 bits.
        int literalBits = sensorValues ? 14 : 21;
        int prefix = sensorValues ? 128 : 1;
        if (bits.readBits(32) != 0 || bits.readBits(32 - literalBits) != prefix) {
            throw new IOException("Unsupported opening of a 14-bit CRX plane");
        }
        int value = reconstruct(HALF_RANGE, bits.readBits(literalBits));
        output[0] = value;
        for (int x = 1; x < width; x++) {
            int unsigned = state.readPredictionResidual();
            value = reconstruct(value, unsigned);
            output[x] = value;
            state.updateK(unsigned);
        }
    }

    private void readRow(int[] previousRow, int[] output, int width) throws IOException {
        int x = 0;
        while (x < width) {
            int above = previousRow[x];
            int left = x == 0 ? above : output[x - 1];
            int aboveLeft = x == 0 ? above : previousRow[x - 1];
            int aboveRight = x + 1 == width ? above : previousRow[x + 1];
            int prediction = CrxMedPredictor.med(left, above, aboveLeft);

            if (x + 1 < width && left == above && left == aboveRight && bits.readBit() == 1) {
                int end = x + state.readRunLength(width - x);
                while (x < end) {
                    output[x] = left;
                    x++;
                }
                if (x == width) {
                    return;
                }
                above = previousRow[x];
                aboveRight = x + 1 == width ? above : previousRow[x + 1];
                prediction = above;
            }

            int unsigned = state.readPredictionResidual();
            output[x] = reconstruct(prediction, unsigned);
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
