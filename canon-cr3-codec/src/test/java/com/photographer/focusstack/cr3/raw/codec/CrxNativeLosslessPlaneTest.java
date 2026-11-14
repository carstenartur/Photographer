package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrxNativeLosslessPlaneTest {
    @Test
    void decodesLeftRowThreeCopiesNorthInterruptionAndMedianIntoTheChosenCfaSites() throws IOException {
        // Prefix, four regular sites; next line starts with 1110 (three copies, then an interruption).
        byte[] bytes = HexFormat.of().parseHex("abcd000000000020305f800933da80ff");
        int[] pixels = new int[50];
        Arrays.fill(pixels, -1);
        CrxNativeLosslessPlane.decode(bytes, 2, bytes.length - 3, 5, 2, pixels, 12, 13);
        int[] expected = new int[50];
        Arrays.fill(expected, -1);
        int[][] rows = {{2000, 2000, 2009, 2004, 2003}, {2000, 2000, 2000, 2005, 2003}};
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length; x++) {
                expected[13 + 24 * y + 2 * x] = rows[y][x];
            }
        }
        assertThat(pixels).containsExactly(expected);
    }

    @Test
    void preservesRunStateAcrossRowsAndOnlyAdvancesOnGroupsThatFit() throws IOException {
        // Ten flat rows followed by a 64-site run interrupted at the right edge.
        // The last group overshoots several row ends; the run index reaches 22.
        byte[] bytes = HexFormat.of().parseHex("000000000020305f97ffffffffffffffffffffffffe7e4");
        int stride = 130;
        int[] pixels = new int[24 * stride];
        CrxNativeLosslessPlane.decode(bytes, 0, bytes.length, 65, 12, pixels, stride, 0);
        int[] expected = new int[pixels.length];
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 65; x++) {
                expected[2 * (y * stride + x)] = 2000;
            }
        }
        expected[2 * (11 * stride + 64)] = 2001;
        assertThat(pixels).containsExactly(expected);
    }

    @Test
    void readsOpeningLiteralAndRejectsTruncationInsideTheSelectedSlice() throws IOException {
        byte[] bytes = HexFormat.of().parseHex("0000000000202f35000000000040020a");
        int[] pixels = new int[3];
        CrxNativeLosslessPlane.decode(bytes, 0, bytes.length, 2, 1, pixels, 3, 0);
        assertThat(pixels).containsExactly(2149, 0, 2018);
        assertThatThrownBy(() -> CrxNativeLosslessPlane.decode(bytes, 0, bytes.length - 1, 2, 1, pixels, 3, 0))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsWrongPhysicalOriginUnsupportedOpeningAndInvalidSample() {
        int[] pixels = new int[4];
        for (String hex : new String[]{"0100000000200000e0", "0000000000300000e0", "0000000000203fffe0",
                "0000000000200000000000000020"}) {
            byte[] bytes = HexFormat.of().parseHex(hex);
            assertThatThrownBy(() -> CrxNativeLosslessPlane.decode(bytes, 0, bytes.length, 2, 1, pixels, 4, 0))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsOverflowAndSitesCrossingARowBeforeWriting() {
        byte[] bytes = HexFormat.of().parseHex("0000000000200000");
        int[] pixels = new int[12];
        assertThatThrownBy(() -> CrxNativeLosslessPlane.decode(bytes, 1, Integer.MAX_VALUE, 1, 1, pixels, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrxNativeLosslessPlane.decode(bytes, 0, 8, 2, 1, pixels, 4, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrxNativeLosslessPlane.decode(bytes, 0, 8, 1, Integer.MAX_VALUE, pixels, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pixels).containsOnly(0);
    }
}
