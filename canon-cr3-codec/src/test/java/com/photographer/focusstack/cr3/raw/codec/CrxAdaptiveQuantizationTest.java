package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrxAdaptiveQuantizationTest {
    // A single column of five map cells. The 32-bit literal opens at 150.
    // The following Rice residuals are 0, +2, +2, +2, so the last cell has no paired row.
    private static final byte[] ODD_ROWS = {0, 0, 1, 44, (byte) 0x84, 0x44};

    @Test
    void expandsInGroupsUsingAveragedPairsAndAnUnpairedLastRow() throws IOException {
        var map = CrxAdaptiveQuantization.read(new BitReader(ODD_ROWS), 16, 20);
        var band = new CrxWaveletTile.Band(2, 3, row -> Arrays.fill(row, -2));
        var expanded = map.expand(band, 4, 4);
        int[] row = new int[2];
        expanded.rows().read(row);
        assertArrayEquals(new int[]{-12, -12}, row); // Average 150, factor 6.
        expanded.rows().read(row);
        assertArrayEquals(new int[]{-18, -18}, row); // Average 153, factor 9.
        expanded.rows().read(row);
        assertArrayEquals(new int[]{-24, -24}, row); // Unpaired 156, factor 12.
    }

    @Test
    void rejectsQuantizationMapsOutsideTheMeasuredProfile() {
        byte[] outOfRange = ODD_ROWS.clone();
        outOfRange[3] = 42; // The opening sample is 149.
        assertThrows(IOException.class,
                () -> CrxAdaptiveQuantization.read(new BitReader(outOfRange), 16, 20));
        byte[] unsignedOverflow = ODD_ROWS.clone();
        unsignedOverflow[0] = (byte) 0x80;
        assertThrows(IOException.class,
                () -> CrxAdaptiveQuantization.read(new BitReader(unsignedOverflow), 16, 20));
        assertThrows(IOException.class,
                () -> CrxAdaptiveQuantization.read(new BitReader(Arrays.copyOf(ODD_ROWS, 5)), 16, 20));
        assertThrows(IOException.class,
                () -> CrxAdaptiveQuantization.read(new BitReader(Arrays.copyOf(ODD_ROWS, 7)), 16, 20));
    }
}
