package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;
import java.util.Arrays;

/** The signed high-frequency coefficients of one independently coded CRX subband. */
final class CrxWaveletDetail {
    private CrxWaveletDetail() {}

    /** The slice ends at the last entropy byte, excluding the header's padding byte count. */
    static int[] decode(byte[] data, int offset, int length, int width, int height, int factor)
            throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IOException("Invalid CRX detail payload extent");
        }
        long count = (long) width * height;
        if (width <= 0 || height <= 0 || count > Integer.MAX_VALUE - 8 || factor < 0) {
            throw new IOException("Invalid CRX detail geometry or quantization factor");
        }

        var bits = new BitReader(data, offset, length);
        var state = new CrxEntropyState(bits, 0);
        int[] coefficients = new int[(int) count];
        // Each slot holds the Rice width selected immediately after coding the site above.
        int[] previousK = new int[width];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int x = 0;
            while (x < width) {
                int site = row + x;
                boolean guard = x + 1 < width
                        && (x == 0 || coefficients[site - 1] == 0)
                        && (y == 0 || (coefficients[site - width] == 0 && coefficients[site - width + 1] == 0));
                if (guard && bits.readBit() == 1) {
                    int end = x + state.readRunLength(width - x);
                    Arrays.fill(previousK, x, end, 0);
                    x = end;
                    if (x == width) {
                        break;
                    }
                }

                int unsigned = state.readResidual();
                int symbol = unsigned + (guard ? 1 : 0);
                coefficients[row + x] = CrxResidualSignMapper.decode(symbol, CrxResidualSignMapper.Mapping.ZIG_ZAG);
                state.updateK(unsigned);
                if (y > 0 && x + 1 < width && state.k() + 1 < previousK[x + 1]) {
                    state.setK(state.k() + 1);
                }
                previousK[x] = state.k();
                x++;
            }
        }
        if (bits.consumedBytes() != length) {
            throw new IOException("Unconsumed bytes at the end of a CRX detail subband");
        }

        // Run decisions above use quantized coefficients, including when the factor is zero.
        if (factor != 1) {
            for (int i = 0; i < coefficients.length; i++) {
                long expanded = (long) coefficients[i] * factor;
                if (expanded < Integer.MIN_VALUE || expanded > Integer.MAX_VALUE) {
                    throw new IOException("CRX dequantized detail coefficient exceeds the signed integer range");
                }
                coefficients[i] = (int) expanded;
            }
        }
        return coefficients;
    }
}
