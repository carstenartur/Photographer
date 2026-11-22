package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/** An immutable tile-wide map shared by the four Bayer-plane readers. */
final class CrxAdaptiveQuantization {
    private static final int FIRST_QP = 150;
    private static final int LAST_QP = 168;
    private static final int LAST_UNIFORM_QP = 164;
    private static final int QUANTIZER_ORIGIN = 124;
    private static final int RICE_ESCAPE = 23;
    private static final int RICE_LITERAL_BITS = 8;
    private static final int MAX_RICE_WIDTH = 4;

    private final byte[] values;
    private final int width;
    private final int height;
    private final int maximum;

    private CrxAdaptiveQuantization(byte[] values, int width, int height, int maximum) {
        this.values = values;
        this.width = width;
        this.height = height;
        this.maximum = maximum;
    }

    static CrxAdaptiveQuantization read(BitReader bits, int tileWidth, int tileHeight) throws IOException {
        int width = Math.ceilDiv(tileWidth, 16);
        int height = Math.ceilDiv(tileHeight, 4);
        int cells = Math.multiplyExact(width, height);
        long minimumBits = Integer.SIZE + (long) cells - 1;
        if ((long) bits.length() * Byte.SIZE < minimumBits) {
            throw new IOException("CRX quantization map cannot contain the declared grid");
        }
        int literal = bits.readBits(Integer.SIZE);
        if (literal < 0) {
            throw new IOException("Invalid CRX adaptive quantizer opening");
        }
        int first = checkedQp(unfold(literal));
        byte[] values = new byte[cells];
        int maximum = first;
        int k = 2;
        values[0] = (byte) first;
        for (int index = 1; index < width; index++) {
            int symbol = bits.readRice(k, RICE_ESCAPE, RICE_LITERAL_BITS);
            int value = checkedQp(unsigned(values[index - 1]) + unfold(symbol));
            values[index] = (byte) value;
            maximum = Math.max(maximum, value);
            k = adapt(k, symbol);
        }
        for (int y = 1; y < height; y++) {
            int row = y * width;
            int aboveRow = row - width;
            int above = unsigned(values[aboveRow]);
            int aboveLeft = above;
            int left = above;
            for (int x = 0; x < width; x++) {
                int symbol = bits.readRice(k, RICE_ESCAPE, RICE_LITERAL_BITS);
                int value = checkedQp(CrxMedPredictor.med(left, above, aboveLeft) + unfold(symbol));
                values[row + x] = (byte) value;
                maximum = Math.max(maximum, value);
                if (x + 1 == width) {
                    k = adapt(k, symbol);
                } else {
                    int aboveRight = unsigned(values[aboveRow + x + 1]);
                    k = adapt(k, (symbol >>> 1) + Math.abs(aboveRight - above));
                    aboveLeft = above;
                    above = aboveRight;
                }
                left = value;
            }
        }
        if (bits.consumedBytes() != bits.length()) {
            throw new IOException("CRX quantization map does not consume its declared entropy length");
        }
        return new CrxAdaptiveQuantization(values, width, height, maximum);
    }

    private static int checkedQp(int value) throws IOException {
        if (value < FIRST_QP || value > LAST_QP) {
            throw new IOException("Unsupported CRX adaptive quantizer " + value);
        }
        return value;
    }

    private static int unfold(int symbol) {
        return CrxResidualSignMapper.decode(symbol, CrxResidualSignMapper.Mapping.ZIG_ZAG);
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static int adapt(int k, int value) {
        return Math.min(MAX_RICE_WIDTH, CrxEntropyState.adaptK(k, value));
    }

    boolean boundedCoefficients() {
        return 2 * step(maximum) <= CrxWaveletTile.MAX_BOUNDED_DETAIL_FACTOR;
    }

    CrxWaveletTile.Band expand(CrxWaveletTile.Band band, int subband, int gain) {
        if (gain == 0) {
            return band;
        }
        boolean pairRows = subband <= 6;
        int groupWidth = pairRows ? 2 : 4;
        return new CrxWaveletTile.Band(band.width(), band.height(), new CrxWaveletTile.RowReader() {
            private int row;

            @Override
            public void read(int[] output) throws IOException {
                band.rows().read(output);
                int qpRow = pairRows ? 2 * row : row;
                int offset = qpRow * width;
                int nextOffset = Math.min(height - 1, qpRow + 1) * width;
                int column = 0;
                for (int group = 0; column < band.width(); group++) {
                    int index = Math.min(group, width - 1);
                    int qp = unsigned(values[offset + index]);
                    if (pairRows) {
                        qp = (qp + unsigned(values[nextOffset + index])) / 2;
                    }
                    int factor = step(qp) * gain / 8;
                    int end = Math.min(band.width(), column + groupWidth);
                    // The entropy reader's signed coefficients fit 21 bits, and
                    // the largest admitted expansion factor is 204.
                    while (column < end) {
                        output[column++] *= factor;
                    }
                }
                row++;
            }
        });
    }

    private static int step(int qp) {
        int index = qp - QUANTIZER_ORIGIN;
        if (qp <= LAST_UNIFORM_QP) {
            return CrxLossyDecoder.uniformQuantizationStep(index);
        }
        // The observed steps above the uniform table double the preceding six-step group.
        return 2 * CrxLossyDecoder.uniformQuantizationStep(index - 6);
    }
}
