package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;
import java.io.IOException;

/** RG4. Offsets use the same extracted subband origin as the RG3 opening reader. */
final class LosslessFirstRow {
    private LosslessFirstRow() {}

    record Row(int[] samples, int endBit, int finalK) {}

    static Row decode(byte[] payload, int bitDepth, int width) throws IOException {
        if (width < 1) {
            throw new IOException("Expected positive row width");
        }
        var bits = new BitReader(payload);
        int first = LosslessOpeningSamples.readFirst(bits, bitDepth);
        int[] samples = new int[width];
        samples[0] = first;
        int endBit = 32;
        int k = 2;
        for (int x = 1; x < width; x++) {
            var residual = LosslessOpeningSamples.readResidual(bits, k);
            samples[x] = LosslessOpeningSamples.addResidual(samples[x - 1], residual.unsigned());
            k = nextK(k, residual.unsigned());
            endBit += residual.bits();
        }
        return new Row(samples, endBit, k);
    }

    static int nextK(int k, int unsigned) {
        int scale = 1 << k;
        if (2 * unsigned < scale) {
            return Math.max(0, k - 1);
        }
        if (unsigned < 3 * scale) {
            return k;
        }
        return unsigned < 6 * scale ? k + 1 : k + 2;
    }
}
