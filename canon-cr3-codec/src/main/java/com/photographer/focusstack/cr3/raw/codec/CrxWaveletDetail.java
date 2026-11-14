package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/** Sequential high-frequency coefficient rows from one independently coded CRX subband. */
final class CrxWaveletDetail implements CrxWaveletTile.RowReader {
    private final BitReader bits;
    private final CrxEntropyState state;
    private final int width;
    private final int height;
    private final int factor;
    private final int[] previousK;
    private final boolean[] nonzero;
    private int row;

    private CrxWaveletDetail(BitReader bits, int width, int height, int factor) {
        this.bits = bits;
        this.state = new CrxEntropyState(bits, 0);
        this.width = width;
        this.height = height;
        this.factor = factor;
        previousK = new int[width];
        nonzero = new boolean[width];
    }

    static CrxWaveletTile.Band band(byte[] data, int offset, int length, int width, int height, int factor)
            throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IOException("Invalid CRX detail payload extent");
        }
        return band(new BitReader(data, offset, length), width, height, factor);
    }

    static CrxWaveletTile.Band band(BitReader bits, int width, int height, int factor) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE - 8 || factor < 0) {
            throw new IOException("Invalid CRX detail geometry or quantization factor");
        }
        return new CrxWaveletTile.Band(width, height, new CrxWaveletDetail(bits, width, height, factor));
    }

    @Override
    public void read(int[] output) throws IOException {
        // To the left, state already describes this row. At x and x+1 it still describes the row above.
        // The run guard uses quantized coefficients even when dequantization maps them to zero.
        int x = 0;
        while (x < width) {
            boolean guard = x + 1 < width && (x == 0 || !nonzero[x - 1]) && !nonzero[x] && !nonzero[x + 1];
            if (guard && bits.readBit() == 1) {
                int end = x + state.readRunLength(width - x);
                while (x < end) {
                    previousK[x] = 0;
                    nonzero[x] = false;
                    output[x++] = 0;
                }
                if (x == width) {
                    break;
                }
            }
            int unsigned = state.readResidual();
            int symbol = unsigned + (guard ? 1 : 0);
            int coefficient = CrxResidualSignMapper.decode(symbol, CrxResidualSignMapper.Mapping.ZIG_ZAG);
            nonzero[x] = coefficient != 0;
            long expanded = (long) coefficient * factor;
            if (expanded < Integer.MIN_VALUE || expanded > Integer.MAX_VALUE) {
                throw new IOException("CRX dequantized detail coefficient exceeds the signed integer range");
            }
            output[x] = (int) expanded;
            state.updateK(unsigned);
            if (row > 0 && x + 1 < width && state.k() + 1 < previousK[x + 1]) {
                state.setK(state.k() + 1);
            }
            previousK[x++] = state.k();
        }
        if (++row == height && bits.consumedBytes() != bits.length()) {
            throw new IOException("Unconsumed bytes at the end of a CRX detail subband");
        }
    }
}
