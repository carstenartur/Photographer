package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

final class WaveletTestRows {
    private WaveletTestRows() {}

    static int[] collect(CrxWaveletTile.Band band) throws IOException {
        int[] all = new int[band.width() * band.height()];
        int[] row = new int[band.width()];
        for (int y = 0; y < band.height(); y++) {
            band.rows().read(row);
            System.arraycopy(row, 0, all, y * band.width(), band.width());
        }
        return all;
    }
}
