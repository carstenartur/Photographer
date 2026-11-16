package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;
import java.util.Arrays;

/** Sequential high-frequency coefficient rows from one independently coded CRX subband. */
final class CrxWaveletDetail implements CrxWaveletTile.RowReader {
    private static final int NONZERO = 1 << CrxEntropyState.RICE_WIDTH_BITS;

    private final BitReader bits;
    private final CrxEntropyState state;
    private final int width;
    private final int height;
    private final int factor;
    // Each byte holds the Rice width and the nonzero flag for the quantized coefficient.
    private final byte[] history;
    private int row;

    private CrxWaveletDetail(BitReader bits, int width, int height, int factor) {
        this.bits = bits;
        this.state = new CrxEntropyState(bits, 0);
        this.width = width;
        this.height = height;
        this.factor = factor;
        history = new byte[width];
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
        // The left state belongs to this row. Above and aboveRight still belong to the previous row.
        // The guard uses quantized coefficients even when dequantization maps them to zero.
        int x = 0;
        int k = state.k();
        int left = 0;
        int above = history[0];
        while (x < width) {
            int aboveRight = x + 1 < width ? history[x + 1] : 0;
            boolean guard = x + 1 < width && ((left | above | aboveRight) & NONZERO) == 0;
            int code = bits.readDetailRice(k, guard);
            if (code == BitReader.POSITIVE_RUN) {
                int end = x + state.readRunLength(width - x);
                Arrays.fill(history, x, end, (byte) 0);
                Arrays.fill(output, x, end, 0);
                x = end;
                if (x == width) {
                    break;
                }
                aboveRight = x + 1 < width ? history[x + 1] : 0;
                // The residual interrupting a run has no extra flag and still uses the guard bias.
                code = bits.readDetailRice(k, false);
            }
            int symbol = (code >>> CrxEntropyState.RICE_WIDTH_BITS) + (guard ? 1 : 0);
            int coefficient = (symbol >>> 1) ^ -(symbol & 1);
            long expanded = (long) coefficient * factor;
            if (expanded < Integer.MIN_VALUE || expanded > Integer.MAX_VALUE) {
                throw new IOException("CRX dequantized detail coefficient exceeds the signed integer range");
            }
            output[x] = (int) expanded;
            k = code & CrxEntropyState.MAX_RICE_WIDTH;
            if (k + 1 < (aboveRight & CrxEntropyState.MAX_RICE_WIDTH)) {
                k++;
            }
            left = k | ((-symbol >> 31) & NONZERO);
            history[x++] = (byte) left;
            above = aboveRight;
        }
        state.setK(k);
        if (++row == height && bits.consumedBytes() != bits.length()) {
            throw new IOException("Unconsumed bytes at the end of a CRX detail subband");
        }
    }
}
